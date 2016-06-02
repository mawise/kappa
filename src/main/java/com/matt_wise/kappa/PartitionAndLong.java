package com.matt_wise.kappa;

import java.io.Serializable;

/**
 * Class used to keep a partition and another key value
 */
public class PartitionAndLong implements Serializable{

    private int partition;
    private long value;

    public PartitionAndLong(int partition, long value){
        this.partition = partition;
        this.value = value;
    }

    public long getvalue(){
        return value;
    }

    public int getPartition(){
        return partition;
    }

    @Override
    public String toString(){
        return Integer.toString(partition) + " " + Long.toString(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PartitionAndLong that = (PartitionAndLong) o;

        if (partition != that.partition) return false;
        return value == that.value;

    }

    @Override
    public int hashCode() {
        int result = partition;
        result = 31 * result + (int) (value ^ (value >>> 32));
        return result;
    }
}
