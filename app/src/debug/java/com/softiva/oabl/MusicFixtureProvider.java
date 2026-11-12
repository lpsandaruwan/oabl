package com.softiva.oabl;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Instrumentation-only SAF fixture. Not compiled into release builds. */
public final class MusicFixtureProvider extends DocumentsProvider {
    @Override public boolean onCreate(){return true;}
    @Override public boolean isChildDocument(String parent,String child){return "root".equals(parent)||child.startsWith(parent+"/");}
    private File file(String id)throws FileNotFoundException{
        File base=new File(getContext().getFilesDir(),"music-fixtures");
        try{File result="root".equals(id)?base:new File(base,id);if(!result.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator)&&!result.equals(base))throw new FileNotFoundException();return result;}catch(java.io.IOException e){throw new FileNotFoundException();}
    }
    @Override public Cursor queryRoots(String[] projection){MatrixCursor c=new MatrixCursor(new String[]{"root_id","document_id","title","flags"});c.addRow(new Object[]{"root","root","Music test fixtures",0});return c;}
    private MatrixCursor cursor(String[] projection){return new MatrixCursor(projection==null?new String[]{"document_id","_display_name","mime_type","last_modified","_size","flags"}:projection);}
    private void add(MatrixCursor c,String id,File f){MatrixCursor.RowBuilder row=c.newRow();for(String key:c.getColumnNames()){switch(key){case "document_id":row.add(id);break;case "_display_name":row.add(f.getName());break;case "mime_type":row.add(f.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:f.getName().endsWith(".png")?"image/png":"audio/"+f.getName().substring(f.getName().lastIndexOf('.')+1));break;case "last_modified":row.add(f.lastModified());break;case "_size":row.add(f.length());break;case "flags":row.add(0);break;default:row.add(null);}}}
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{MatrixCursor c=cursor(projection);add(c,id,file(id));return c;}
    @Override public Cursor queryChildDocuments(String id,String[] projection,String sort)throws FileNotFoundException{MatrixCursor c=cursor(projection);File[] files=file(id).listFiles();if(files==null)throw new FileNotFoundException();for(File f:files)add(c,"root".equals(id)?f.getName():id+"/"+f.getName(),f);return c;}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY);}
}
