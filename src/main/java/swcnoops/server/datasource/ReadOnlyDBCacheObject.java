package swcnoops.server.datasource;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ReadOnlyDBCacheObject<A> implements DBCacheObjectRead<A> {
    volatile protected A dbObject;
    private Lock lock = new ReentrantLock();
    volatile private long lastLoaded;
    volatile private long dirtyTime;
    private boolean nullAllowed = true;

    public ReadOnlyDBCacheObject(A object, boolean nullAllowed) {
        this.nullAllowed = nullAllowed;
        this.initialise(object);
    }

    public ReadOnlyDBCacheObject(boolean nullAllowed) {
        this(null, nullAllowed);
        // we set it dirty at start to allow the first call to read to go back to the DB
        this.dirtyTime = this.lastLoaded + 1;
    }

    @Override
    public void initialise(A initialDBObject) {
        this.dbObject = initialDBObject;
        this.lastLoaded = System.currentTimeMillis();
    }

    @Override
    public A getObjectForReading() {
        return this.getDBObject();
    }

    protected abstract A loadDBObject();

    protected A getDBObject() {
        A ret = this.dbObject;
        if ((!this.nullAllowed && ret == null) || this.dirtyTime > this.lastLoaded) {
            this.lock.lock();
            try {
                if ((!this.nullAllowed && ret == null) || this.dirtyTime > this.lastLoaded) {
                    setDbObject(loadDBObject());
                }
                ret = this.dbObject;
            } finally {
                this.lock.unlock();
            }
        }

        return ret;
    }

    private void setDbObject(A dbObject) {
        this.dbObject = dbObject;
        this.lastLoaded = System.currentTimeMillis();
    }

    @Override
    public void setDirty() {
        this.dirtyTime = System.currentTimeMillis();
    }
}
