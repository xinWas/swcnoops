package swcnoops.server.datasource;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class DBCacheObjectImpl<A> implements DBCacheObject<A> {
    volatile private A dbObject;
    volatile private long lastLoaded;
    volatile private long dirtyTime;
    private Lock lock = new ReentrantLock();
    volatile private A dbObjectForWrite;

    protected DBCacheObjectImpl() {
    }

    public DBCacheObjectImpl(A initialDBObject) {
        initialise(initialDBObject);
    }

    @Override
    public void initialise(A initialDBObject) {
        setDbObject(initialDBObject);
        this.dirtyTime = 0;
        this.dbObjectForWrite = null;
    }

    protected A getDBObject() {
        A ret = this.dbObject;
        if (ret == null || this.dirtyTime > this.lastLoaded) {
            this.lock.lock();
            try {
                if (ret == null || this.dirtyTime > this.lastLoaded) {
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

    protected abstract A loadDBObject();

    @Override
    public A getObjectForWriting() {
        A obj = this.dbObjectForWrite;
        if (obj == null)
            obj = this.setObjectForSaving(this.getDBObject());

        return obj;
    }

    @Override
    public A getObjectForReading() {
        A obj = this.dbObjectForWrite;
        if (obj != null)
            return obj;

        return this.getDBObject();
    }

    @Override
    public void doneDBSave() {
        // this forces a reload of the data when accessed again, so it does not really matter if the save succeeded
        if (this.dbObjectForWrite != null) {
            this.dbObjectForWrite = null;
            this.setDirty();
        }
    }

    @Override
    public boolean needsSaving() {
        return this.dbObjectForWrite != null;
    }

    @Override
    public A getObjectForSaving() {
        A obj = this.dbObjectForWrite;
        if (obj != null)
            return obj;

        throw new RuntimeException("Nothing to save can not call getObjectForSaving");
    }

    @Override
    public A setObjectForSaving(A object) {
        this.dbObjectForWrite = object;
        return this.dbObjectForWrite;
    }

    @Override
    public void setDirty() {
        this.dirtyTime = System.currentTimeMillis();
    }
}
