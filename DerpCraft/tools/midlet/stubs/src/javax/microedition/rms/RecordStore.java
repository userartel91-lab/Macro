package javax.microedition.rms; public class RecordStore { public static RecordStore openRecordStore(String n,boolean c) throws RecordStoreException {return null;}
 public int getNumRecords() throws RecordStoreException {return 0;} public byte[] getRecord(int i) throws RecordStoreException {return null;} public void setRecord(int i,byte[] b,int o,int l) throws RecordStoreException {}
 public int addRecord(byte[] b,int o,int l) throws RecordStoreException {return 0;} public void closeRecordStore() throws RecordStoreException {} }
