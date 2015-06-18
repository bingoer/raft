package io.tetrapod.raft.storage;

import io.tetrapod.raft.*;

import java.io.*;
import java.util.*;

/**
 * TODO:
 * <ul>
 * <li>Unit Testing
 * <li>Conditional Puts
 * </ul>
 */
public class StorageStateMachine<T extends StorageStateMachine<T>> extends StateMachine<T> {

   protected final Map<String, StorageItem>            items       = new HashMap<>();

   protected final Map<Long, Map<String, StorageItem>> copyOnWrite = new HashMap<>();

   public StorageStateMachine() {
      super();
      registerCommand(PutItemCommand.COMMAND_ID, new CommandFactory<T>() {
         @Override
         public Command<T> makeCommand() {
            return new PutItemCommand<T>();
         }
      });
      registerCommand(RemoveItemCommand.COMMAND_ID, new CommandFactory<T>() {
         @Override
         public Command<T> makeCommand() {
            return new RemoveItemCommand<T>();
         }
      });
      registerCommand(IncrementCommand.COMMAND_ID, new CommandFactory<T>() {
         @Override
         public Command<T> makeCommand() {
            return new IncrementCommand<T>();
         }
      });
      registerCommand(LockCommand.COMMAND_ID, new CommandFactory<T>() {
         @Override
         public Command<T> makeCommand() {
            return new LockCommand<T>();
         }
      });
      registerCommand(UnlockCommand.COMMAND_ID, new CommandFactory<T>() {
         @Override
         public Command<T> makeCommand() {
            return new UnlockCommand<T>();
         }
      });
   }

   @Override
   public void saveState(DataOutputStream out) throws IOException {
      final Map<String, StorageItem> modified = new HashMap<>();

      long writeIndex;
      List<StorageItem> list;

      synchronized (copyOnWrite) {
         writeIndex = getIndex();
         copyOnWrite.put(writeIndex, modified);
         list = new ArrayList<StorageItem>(items.values());
      }

      // now we write the items out, checking the modified list to make sure we write the
      // items as they were at the time of this snapshot index
      out.writeInt(list.size());
      for (StorageItem item : list) {
         synchronized (copyOnWrite) {
            StorageItem modItem = modified.get(item.key);
            if (modItem != null) {
               modItem.write(out);
            } else {
               item.write(out);
            }
         }
      }

      synchronized (copyOnWrite) {
         copyOnWrite.remove(writeIndex);
      }
      modified.clear();
   }

   @Override
   public void loadState(DataInputStream in) throws IOException {
      items.clear();
      copyOnWrite.clear();
      int numItems = in.readInt();
      while (numItems-- > 0) {
         StorageItem item = new StorageItem(in);
         items.put(item.key, item);
      }
   }

   protected void modify(StorageItem item) {
      synchronized (copyOnWrite) {
         for (Map<String, StorageItem> modified : copyOnWrite.values()) {
            // copy it only if it isn't copied yet
            if (!modified.containsKey(item.key)) {
               modified.put(item.key, new StorageItem(item));
            }
         }
      }
   }

   protected void modify(StorageItem item, byte[] data) {
      modify(item);
      if (data != null) {
         item.modify(data);
      } else {
         items.remove(item.key);
      }
   }

   public StorageItem getItem(String key) {
      return items.get(key);
   }

   @Override
   public String toString() {
      // HACK FOR TESTING
      StorageItem item = items.get("foo");
      return item == null ? "<null>" : new String(item.getData());
   }

   protected void putItem(String key, byte[] data) {
      StorageItem item = items.get(key);
      if (item != null) {
         modify(item, data);
      } else {
         items.put(key, new StorageItem(key, data));
      }
   }

   public void removeItem(String key) {
      StorageItem item = items.get(key);
      if (item != null) {
         modify(item, null);
      }
   }

   public long increment(String key, long amount) {
      StorageItem item = items.get(key);
      if (item != null) {
         long val = amount + RaftUtil.toLong(item.getData());
         modify(item, RaftUtil.toBytes(val));
         return val;
      } else {
         items.put(key, new StorageItem(key, RaftUtil.toBytes(amount)));
         return amount;
      }
   }

   public boolean lock(String key, long leaseForMillis) {
      final long curTime = System.currentTimeMillis();
      StorageItem item = items.get(key);
      if (item != null) {
         modify(item);
      } else {
         item = new StorageItem(key, null);
         items.put(key, item);
      }
      return item.lock(leaseForMillis, curTime);
   }

   public void unlock(String key) {
      final StorageItem item = items.get(key);
      if (item != null) {
         modify(item);
         item.unlock();
      }
   }

}