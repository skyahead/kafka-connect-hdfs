/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.hdfs.wal;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import io.confluent.connect.hdfs.FileUtils;
import io.confluent.connect.hdfs.storage.Storage;
import io.confluent.connect.hdfs.wal.WALFile.Reader;
import io.confluent.connect.hdfs.wal.WALFile.Writer;

public class FSWAL implements WAL {

  private static final Logger log = LoggerFactory.getLogger(FSWAL.class);
  private static final String leaseException =
      "org.apache.hadoop.hdfs.protocol.AlreadyBeingCreatedException";

  private WALFile.Writer writer = null;
  private WALFile.Reader reader = null;
  private String logFile = null;
  private Configuration conf = null;
  private Storage storage = null;

  public FSWAL(String logsDir, TopicPartition topicPart, Storage storage)
      throws ConnectException {
    this.storage = storage;
    this.conf = storage.conf();
    String url = storage.url();
    logFile = FileUtils.logFileName(url, logsDir, topicPart);
  }

  @Override
  public void append(String tempFile, String committedFile) throws ConnectException {
    try {
      acquireLease();
      WALEntry key = new WALEntry(tempFile);
      WALEntry value = new WALEntry(committedFile);
      writer.append(key, value);
      writer.hsync();
    } catch (IOException e) {
      throw new ConnectException(e);
    }
  }

  public boolean canRecover(String fileName) throws ConnectException {
    boolean flag = false;
    int MAX_RETRY = 10;
    for (int i=0; i<MAX_RETRY; i++) {
      try {
        boolean recovered = storage.recoverFileLease(new Path(fileName));
        if (recovered) {
          log.info("Recovering file {} successfully.", fileName);
          flag = true;
          break;
        } else {
          log.info("Recovering file {} failed, remaining retires {}.", fileName, MAX_RETRY-i-1);
        }
        try {
          Thread.sleep(1000);
        } catch (Exception se) {
          // does nothing
        }
      } catch (IOException ioe) {
        throw new ConnectException("Error recovering file " + fileName, ioe);
      }
    }

    return flag;
  }

  public void acquireLease() throws ConnectException {
    long sleepIntervalMs = 1000L;
    long MAX_SLEEP_INTERVAL_MS = 16000L;
    while (sleepIntervalMs < MAX_SLEEP_INTERVAL_MS) {
      try {
        if (writer == null) {
          writer = WALFile.createWriter(conf, Writer.file(new Path(logFile)),
                                        Writer.appendIfExists(true));
          log.info("Successfully acquired lease for {}", logFile);
        }
        break;
      } catch (RemoteException e) {
        if (e.getMessage().contains("already the current lease holder")) {
          // we are already holding a lease, should not have tried in the first place. Just return!
          return;
        }

        if (e.getClassName().equals(leaseException)) {
          log.info("Cannot acquire lease on WAL {}", logFile);

          if (!canRecover(logFile)) {
            try {
              Thread.sleep(sleepIntervalMs);
            } catch (InterruptedException ie) {
              throw new ConnectException(ie);
            }
          }

          sleepIntervalMs = sleepIntervalMs * 2;
        } else {
          throw new ConnectException(e);
        }
      } catch (IOException e) {
        if (e.getMessage().contains("Cannot obtain block length") && canRecover(logFile)) {
          // WAL file is corrupted due to things like i) hdfs is restarted, hard disk full, etc
          // let us try to force recovering it
          return;
        } else {
          throw new ConnectException("Error creating writer for log file " + logFile, e);
        }
      }
    }
    if (sleepIntervalMs >= MAX_SLEEP_INTERVAL_MS) {
      throw new ConnectException("Cannot acquire lease after timeout, will retry.");
    }
  }

  @Override
  public void apply() throws ConnectException {
    try {
      if (!storage.exists(logFile)) {
        return;
      }
      acquireLease();
      if (reader == null) {
        reader = new WALFile.Reader(conf, Reader.file(new Path(logFile)));
      }
      Map<WALEntry, WALEntry> entries = new HashMap<>();
      WALEntry key = new WALEntry();
      WALEntry value = new WALEntry();
      while (reader.next(key, value)) {
        String keyName = key.getName();
        if (keyName.equals(beginMarker)) {
          entries.clear();
        } else if (keyName.equals(endMarker)) {
          for (Map.Entry<WALEntry, WALEntry> entry: entries.entrySet()) {
            String tempFile = entry.getKey().getName();
            String committedFile = entry.getValue().getName();
            if (!storage.exists(committedFile)) {
              storage.commit(tempFile, committedFile);
            }
          }
        } else {
          WALEntry mapKey = new WALEntry(key.getName());
          WALEntry mapValue = new WALEntry(value.getName());
          entries.put(mapKey, mapValue);
        }
      }
    } catch (IOException e) {
      if (e.getMessage().contains("Cannot obtain block length") && canRecover(logFile)) {
        // this can happen when the WAL file is corrupted
        return;
      }

      throw new ConnectException(e);
    }
  }

  @Override
  public void truncate() throws ConnectException {
    try {
      String oldLogFile = logFile + ".1";
      storage.delete(oldLogFile);
      storage.commit(logFile, oldLogFile);
      // Clean out references to the current WAL file.
      // Open a new one on the next lease acquisition.
      close();
    } catch (IOException e) {
      throw new ConnectException(e);
    }
  }

  @Override
  public void close() throws ConnectException {
    try {
      if (writer != null) {
        writer.close();
        writer = null;
      }
      if (reader != null) {
        reader.close();
        reader = null;
      }
    } catch (IOException e) {
      throw new ConnectException("Error closing " + logFile, e);
    }
  }

  @Override
  public String getLogFile() {
    return logFile;
  }
}
