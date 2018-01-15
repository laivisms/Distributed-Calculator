package edu.yu.distributed.DistributedCalculator;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class WorkerData {
	
	private HashMap<String, BigDecimal> numbers = new HashMap<String, BigDecimal>();
	
	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	
	
	/**
	 * 
	 * @param key
	 * @return the value associated with key if exists, null otherwise
	 */
	public BigDecimal get(String key) {
		
		BigDecimal result = null;
		
		if(!numbers.containsKey(key))
			return result;
		
		lock.readLock().lock();
		
		
		result = numbers.get(key);
		
		
		lock.readLock().unlock();
		
		return result;
		
	}
	
	
	public void put(String key, BigDecimal value) {
		
		lock.writeLock().lock();
		
		numbers.put(key, value);
		
		lock.writeLock().unlock();
		
	}
	
	public boolean remove(String key) {
		
		if(!numbers.containsKey(key))
			return false;
		
		lock.writeLock().lock();
		
		numbers.remove(key);
		
		lock.writeLock().unlock();
		
		return true;
	}
	
	public boolean contains(String key) {
		return numbers.containsKey(key);
	}

}
