/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.yu.distributed.DistributedCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Auxiliary cache to handle changes to the lists of tasks and of workers.
 */

public class ChildrenCache {

    protected List<String> children;
    
    
    //@author Laivi MS
    protected HashMap<String, String> keyToWorker = new HashMap<String, String>();
    protected HashMap<String, CalculationTask> taskNameToTask = new HashMap<String, CalculationTask>();
    
    ChildrenCache() {
        this.children = null;        
    }
    
    ChildrenCache(List<String> children) {
        this.children = children; 
    }
        
    List<String> getList() {
        return children;
    }
        
    List<String> addedAndSet( List<String> newChildren) {
        ArrayList<String> diff = null;
        
        if(children == null) {
            diff = new ArrayList<String>(newChildren);
        } else {
            for(String s: newChildren) {
                if(!children.contains( s )) {
                    if(diff == null) {
                        diff = new ArrayList<String>();
                    }
                
                    diff.add(s);
                }
            }
        }
        this.children = newChildren;
            
        return diff;
    }
        
    List<String> removedAndSet( List<String> newChildren) {
        List<String> diff = null;
            
        if(children != null) {
            for(String s: children) {
                if(!newChildren.contains( s )) {
                    if(diff == null) {
                        diff = new ArrayList<String>();
                    }
                    
                    diff.add(s);
                }
            }
        }
        this.children = newChildren;
        
        return diff;
    }
    
    /**
     * @author Laivi MS
     */
    void associate(String key, String worker) {
    	keyToWorker.put(key, worker);
    }
    
    String getWorker(String key) {
    	return keyToWorker.get(key);
    }
    
    void associateTask(String taskName, String request, int partCount) {
    	taskNameToTask.put(taskName, new CalculationTask(request, taskName, partCount));
    }
    
    boolean addCaclulationPart(String task, String part) {
    	return taskNameToTask.get(task).addPart(part);
    }
    
    String[] getCalculationParts(String task) {
    	return taskNameToTask.get(task).getOrderedParts();
    }
    
    public class CalculationTask {
    	
    	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    	
    	PriorityQueue<RequestOrderWrapper> parts = new PriorityQueue<RequestOrderWrapper>();
    	
    	String request;
    	String taskName;
    	
    	int partCount;
    	
    	public CalculationTask(String request, String taskName, int partCount) {
    		this.request = request;
    		this.taskName = taskName;
    		this.partCount = partCount;
    	}
    	
    	public void setPartCount(int count) {
    		partCount = count;
    	}
    	
    	public int getPartCount() {
    		return partCount;
    	}
    	
    	/**
    	 * 
    	 * @return true if, with the addition of this part, the task now has all parts answered, false otherwise
    	 */
    	public boolean addPart(String part) {
    		
    		lock.writeLock().lock();
    		
    		boolean result = false;
    		
    		int firstSpace = part.indexOf(" ");
    		int secondSpace = part.indexOf(" ", firstSpace + 1);
    		
    		int partNumber = Integer.parseInt(part.substring(firstSpace + 1, secondSpace));
    		
    		parts.add(new RequestOrderWrapper(part, partNumber));
    		
    		if(parts.size() >= partCount) { //we have received all the parts of the calculation
    			result = true;
    		}
    		
    		lock.writeLock().unlock();
    		
    		return result;
    	}
    	
    	public String[] getOrderedParts(){
    		String[] result = new String[partCount];
    		
    		int counter = 0;
    		
    		while(!parts.isEmpty()) {
    			result[counter] = parts.poll().answer;
    			counter++;
    		}
    		
    		return result;
    	}
    	
    	class RequestOrderWrapper implements Comparable<RequestOrderWrapper>{
    		String answer;
    		int priority = 0;
    		
    		public RequestOrderWrapper(String answer, int order) {
    			this.answer = answer;
    			this.priority = order;
    		}
    		
    		public int getPriority() {
    			return priority;
    		}

			@Override
			public int compareTo(RequestOrderWrapper ROW) {
				
				return priority - ROW.getPriority();
				
			}
    		
    		
    	}
    }
    
    
}
