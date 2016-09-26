/*
   Copyright 2015 The Trustees of Princeton University

   Licensed under the Apache License, Version 2.0 (the "License" );
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package hsyndicate.fs;

import com.google.common.primitives.UnsignedLong;
import com.sun.jersey.api.client.ClientResponse;
import hsyndicate.rest.client.SyndicateUGHttpClient;
import hsyndicate.rest.datatypes.FileDescriptor;
import hsyndicate.utils.BlockUtils;
import hsyndicate.utils.IOUtils;
import hsyndicate.utils.IPUtils;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Future;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SyndicateFSFileHandle implements Closeable {
    
    private static final Log LOG = LogFactory.getLog(SyndicateFSFileHandle.class);

    private SyndicateFileSystem filesystem;
    private SyndicateFSFileStatus status;
    private FileDescriptor fileDescriptor;
    private boolean readonly = false;
    private boolean closed = true;
    private boolean modified = false;
    private long blockSize;
    private boolean localFileSystem;
    private Map<UnsignedLong, File> localCachedBlocks;
    private Thread keepaliveThread;

    class KeepaliveWorker implements Runnable {
        private SyndicateFSFileHandle handle;
        private SyndicateFSPath path;
        private FileDescriptor fd;
        
        private static final long NOTIFY_PERIOD = 60 * 1000;

        private KeepaliveWorker(SyndicateFSFileHandle handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            // notify that the handle is still in use periodically
            try {
                while(true) {
                    Thread.sleep(NOTIFY_PERIOD);
                    this.handle.tryExtendTTL();
                }
            } catch (InterruptedException ex) {
                // silient ignore
            } catch (Exception ex) {
                LOG.error("exception occurred", ex);
            }
        }
    }
    
    SyndicateFSFileHandle(SyndicateFileSystem fs, SyndicateFSFileStatus status, FileDescriptor fd, boolean readonly) {
        this.filesystem = fs;
        this.status = status;
        this.fileDescriptor = fd;
        this.readonly = readonly;
        this.closed = false;
        this.modified = false;

        this.blockSize = status.getBlockSize();
        
        String host = this.filesystem.getConfiguration().getHost();
        if(IPUtils.isLocalIPAddress(host)) {
            this.localFileSystem = true;
            try {
                this.localCachedBlocks = fs.listLocalCachedBlocks(status.getPath());
            } catch (IOException ex) {
                LOG.info("exception raised while reading cache info : " + ex.toString());
                this.localCachedBlocks = null;
            }
        } else {
            this.localFileSystem = false;
        }
        
        this.keepaliveThread = new Thread(new KeepaliveWorker(this));
        this.keepaliveThread.start();
        LOG.info("file opened - " + this.status.getPath().getPath());
    }
    
    public synchronized SyndicateFileSystem getFileSystem() {
        return this.filesystem;
    }
    
    public synchronized SyndicateFSPath getPath() {
        return this.status.getPath();
    }
    
    public synchronized SyndicateFSFileStatus getStatus() {
        return this.status;
    }
    
    public synchronized void extendTTL() throws IOException {
        if(this.closed) {
            throw new IOException("File handle is closed");
        }
        
        SyndicateUGHttpClient client = this.filesystem.getUGRestClient();
        Future<ClientResponse> extendTtlFuture = client.extendTtl(this.status.getPath().getPath(), this.fileDescriptor);
        if (extendTtlFuture != null) {
            try {
                client.processExtendTtl(extendTtlFuture);
            } catch (Exception ex) {
                LOG.error("exception occurred", ex);
                throw new IOException(ex);
            }
        } else {
            throw new IOException("Can not create a REST client");
        }
    }
    
    public synchronized void tryExtendTTL() {
        if(!this.closed) {
            try {
                SyndicateUGHttpClient client = this.filesystem.getUGRestClient();
                Future<ClientResponse> extendTtlFuture = client.extendTtl(this.status.getPath().getPath(), this.fileDescriptor);
                if (extendTtlFuture != null) {
                    client.processExtendTtl(extendTtlFuture);
                }
            } catch (Exception ex) {}
        }
    }
    
    protected synchronized InputStream readFileDataBlockInputStream(int blockID) throws IOException {
        if(blockID < 0) {
            throw new IllegalArgumentException("blockID must be positive");
        }
        
        // read from local if available
        if(this.localFileSystem && this.localCachedBlocks != null) {
            // check the cached file block is present
            File cachedBlockFile = this.localCachedBlocks.get(UnsignedLong.asUnsigned(blockID));
            if(cachedBlockFile != null) {
                // has cache
                if(cachedBlockFile.exists()) {
                    LOG.info("read from local cache file");
                    return new FileInputStream(cachedBlockFile);
                }
            }
        }
        
        // otherwise
        SyndicateUGHttpClient client = this.filesystem.getUGRestClient();
        Future<ClientResponse> readFuture = client.read(this.status.getPath().getPath(), this.fileDescriptor, BlockUtils.getBlockStartOffset(blockID, this.blockSize), (int) this.blockSize);
        if(readFuture != null) {
            InputStream readIS;
            try {
                readIS = client.processRead(readFuture);
            } catch (Exception ex) {
                LOG.error("exception occurred", ex);
                throw new IOException(ex);
            }
            
            if(readIS == null) {
                LOG.error("failed to read file - " + this.status.getPath().getPath());
                throw new IOException("failed to read file - " + this.status.getPath().getPath());
            }
            
            return readIS;
        } else {
            throw new IOException("Can not create a REST client");
        }
    }
    
    public synchronized SyndicateFSReadBlockData readFileDataBlock(int blockID) throws IOException {
        if(this.closed) {
            throw new IOException("File handle is closed");
        }
        
        LOG.info("reading a block " + blockID);
        InputStream is = readFileDataBlockInputStream(blockID);
        LOG.info("obtained an inputStream for the block " + blockID);
        byte[] buffer = IOUtils.toByteArray(is);
        
        return new SyndicateFSReadBlockData(BlockUtils.getBlockStartOffset(blockID, this.blockSize), buffer, (int) this.blockSize);
    }
    
    protected synchronized void _writeFileDataBlock(int blockID, byte[] buffer, int size) throws IOException {
        if(blockID < 0) {
            throw new IllegalArgumentException("blockID must be positive");
        }
        
        if(this.readonly) {
            throw new IOException("Can not write data to readonly handle");
        }
        
        SyndicateUGHttpClient client = this.filesystem.getUGRestClient();
        Future<ClientResponse> writeFuture = client.write(this.status.getPath().getPath(), this.fileDescriptor, BlockUtils.getBlockStartOffset(blockID, this.blockSize), size, buffer);
        if(writeFuture != null) {
            try {
                client.processWrite(writeFuture);
            } catch (Exception ex) {
                LOG.error("exception occurred", ex);
                throw new IOException(ex);
            }
        } else {
            throw new IOException("Can not create a REST client");
        }
        
        if(this.status.getSize() <= BlockUtils.getBlockStartOffset(blockID, this.blockSize) + size) {
            this.status.notifySizeChanged(BlockUtils.getBlockStartOffset(blockID, this.blockSize) + size);
        }
        
        this.modified = true;
    }
    
    public synchronized void writeFileDataBlockByteArray(int blockID, byte[] buffer, int size) throws IOException {
        if(this.closed) {
            throw new IOException("File handle is closed");
        }
        
        LOG.info("writing a block " + blockID);
        byte[] buffer_cpy = new byte[size];
        System.arraycopy(buffer, 0, buffer_cpy, 0, size);
        _writeFileDataBlock(blockID, buffer_cpy, size);
    }
    
    public synchronized boolean isOpen() {
        return !this.closed;
    }
    
    public synchronized boolean isReadonly() {
        return this.readonly;
    }
    
    @Override
    public synchronized void close() throws IOException {
        if(!this.closed) {
            LOG.info("closing a file");
            
            if(this.keepaliveThread != null && this.keepaliveThread.isAlive()) {
                this.keepaliveThread.interrupt();
            }
            this.keepaliveThread = null;
            
            Future<ClientResponse> closeFuture = this.filesystem.getUGRestClient().close(this.status.getPath().getPath(), this.fileDescriptor);
            if(closeFuture != null) {
                try {
                    this.filesystem.getUGRestClient().processClose(closeFuture);
                } catch (Exception ex) {
                    LOG.error("exception occurred", ex);
                    throw new IOException(ex);
                }
            } else {
                throw new IOException("Can not create a REST client");
            }

            this.closed = true;

            if(!this.readonly) {
                if(this.modified) {
                    this.status.setDirty();
                }
            }

            if(this.localCachedBlocks != null) {
                this.localCachedBlocks.clear();
            }
        }
    }
}
