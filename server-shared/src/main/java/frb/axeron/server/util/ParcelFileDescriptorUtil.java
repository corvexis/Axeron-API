package frb.axeron.server.util;


import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ParcelFileDescriptorUtil {

    public static ParcelFileDescriptor pipeFrom(InputStream inputStream) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readSide = pipe[0];
        ParcelFileDescriptor writeSide = pipe[1];

        PipeTransferPool.submit(new TransferRunnable(inputStream, new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)));

        return readSide;
    }

    public static ParcelFileDescriptor pipeTo(OutputStream outputStream) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readSide = pipe[0];
        ParcelFileDescriptor writeSide = pipe[1];

        PipeTransferPool.submit(new TransferRunnable(new ParcelFileDescriptor.AutoCloseInputStream(readSide), outputStream));

        return writeSide;
    }

    public static class TransferRunnable implements Runnable {
        final InputStream mIn;
        final OutputStream mOut;

        public TransferRunnable(InputStream in, OutputStream out) {
            mIn = in;
            mOut = out;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            int len;

            try {
                while ((len = mIn.read(buf)) > 0) {
                    mOut.write(buf, 0, len);
                    mOut.flush();
                }
            } catch (IOException e) {
                Log.e("TransferRunnable", Log.getStackTraceString(e));
            } finally {
                try {
                    mIn.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    mOut.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
