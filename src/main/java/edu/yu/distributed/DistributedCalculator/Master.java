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

import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;

import edu.yu.distributed.DistributedCalculator.recovery.RecoveredAssignments;
import edu.yu.distributed.DistributedCalculator.recovery.RecoveredAssignments.RecoveryCallback;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class implements the master of the master-worker example we use
 * throughout the book. The master is responsible for tracking the list of
 * available workers, determining when there are new tasks and assigning
 * them to available workers. 
 * 
 * The flow without crashes is like this. The master reads the list of
 * available workers and watch for changes to the list of workers. It also
 * reads the list of tasks and watches for changes to the list of tasks.
 * For each new task, it assigns the task to a worker chosen at random.
 * 
 * Before exercising the role of master, this ZooKeeper client first needs
 * to elect a primary master. It does it by creating a /master znode. If
 * it succeeds, then it exercises the role of master. Otherwise, it watches
 * the /master znode, and if it goes away, it tries to elect a new primary
 * master.
 * 
 * The states of this client are three: RUNNING, ELECTED, NOTELECTED. 
 * RUNNING means that according to its view of the ZooKeeper state, there
 * is no primary master (no master has been able to acquire the /master lock).
 * If some master succeeds in creating the /master znode and this master learns
 * it, then it transitions to ELECTED if it is the primary and NOTELECTED
 * otherwise.
 *   
 * Because workers may crash, this master also needs to be able to reassign
 * tasks. When it watches for changes in the list of workers, it also 
 * receives a notification when a znode representing a worker is gone, so 
 * it is able to reassign its tasks.
 * 
 * A primary may crash too. In the case a primary crashes, the next primary
 * that takes over the role needs to make sure that it assigns and reassigns
 * tasks that the previous primary hasn't had time to process.
 *
 */
public class Master implements Watcher, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(Master.class);
    
    /*
     * A master process can be either running for
     * primary master, elected primary master, or
     * not elected, in which case it is a backup
     * master.  
     */
    enum MasterStates {RUNNING, ELECTED, NOTELECTED};
    
    enum RequestType {INSERT, DELETE, RETRIEVE, CALCULATE, ERROR};

    private volatile MasterStates state = MasterStates.RUNNING;
    
    MasterStates getState() {
        return state;
    }
    
    private Random random = new Random(this.hashCode());
    private ZooKeeper zk;
    private String hostPort;
    private String serverId = Integer.toHexString( random.nextInt() );
    private volatile boolean connected = false;
    private volatile boolean expired = false;
    
    
    protected ChildrenCache tasksCache;
    protected ChildrenCache workersCache;
    
    //@author Laivi Malamut-Salvaggio {
    private ThreadPoolExecutor executor;
    
    private Worker localBackupWorker;
    //}
    
    /**
     * Creates a new master instance.
     * 
     * @param hostPort
     */
    Master(String hostPort) { 
        this.hostPort = hostPort;
        localBackupWorker = new Worker(hostPort);
        //@author Laivi Malamut-Salvaggio {
        this.executor = new ThreadPoolExecutor(5, 10, 
                1000L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(200));
        //}
    }
    
    
    /**
     * Creates a new ZooKeeper session.
     * 
     * @throws IOException
     */
    void startZK() throws IOException {
        zk = new ZooKeeper(hostPort, 15000, this);
    }

    /**
     * Closes the ZooKeeper session.
     *
     * @throws IOException
     */
    void stopZK() throws InterruptedException, IOException {
        zk.close();
    }
    
    /**
     * This method implements the process method of the
     * Watcher interface. We use it to deal with the
     * different states of a session. 
     * 
     * @param e new session event to be processed
     */
    public void process(WatchedEvent e) {  
        LOG.info("Processing event: " + e.toString());
        if(e.getType() == Event.EventType.None){
            switch (e.getState()) {
            case SyncConnected:
                connected = true;
                break;
            case Disconnected:
                connected = false;
                break;
            case Expired:
                expired = true;
                connected = false;
                LOG.error("Session expiration");
            default:
                break;
            }
        }
    }
    
    
    /**
     * This method creates some parent znodes we need for this example.
     * In the case the master is restarted, then this method does not
     * need to be executed a second time.
     */
    public void bootstrap(){
        createParent("/workers", new byte[0]);
        createParent("/assign", new byte[0]);
        createParent("/tasks", new byte[0]);
        createParent("/status", new byte[0]);
        createParent("/completed", new byte[0]);
    }
    
    void createParent(String path, byte[] data){
    	//the purpose of this zk access is to initialize all the folders we will be useing, if they dont already exist
        zk.create(path, 
                data, 
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT,
                createParentCallback, 
                data);
    }
    
    StringCallback createParentCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                /*
                 * Try again. Note that registering again is not a problem.
                 * If the znode has already been created, then we get a 
                 * NODEEXISTS event back.
                 */
                createParent(path, (byte[]) ctx);
                
                break;
            case OK:
                LOG.info("Parent created");
                
                break;
            case NODEEXISTS:
                LOG.warn("Parent already registered: " + path);
                
                break;
            default:
                LOG.error("Something went wrong: ", 
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };
        
    /**
     * Check if this client is connected.
     * 
     * @return boolean ZooKeeper client is connected
     */
    boolean isConnected() {
        return connected;
    }
    
    /**
     * Check if the ZooKeeper session has expired.
     * 
     * @return boolean ZooKeeper session has expired
     */
    boolean isExpired() {
        return expired;
    }

    /*
     **************************************
     **************************************
     * Methods related to master election.*
     **************************************
     **************************************
     */
    
    
    /*
     * The story in this callback implementation is the following.
     * We tried to create the master lock znode. If it suceeds, then
     * great, it takes leadership. However, there are a couple of
     * exceptional situations we need to take care of. 
     * 
     * First, we could get a connection loss event before getting
     * an answer so we are left wondering if the operation went through.
     * To check, we try to read the /master znode. If it is there, then
     * we check if this master is the primary. If not, we run for master
     * again. 
     * 
     *  The second case is if we find that the node is already there.
     *  In this case, we call exists to set a watch on the znode.
     *
    StringCallback masterCreateCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                checkMaster();
                
                break;
            case OK:
                state = MasterStates.ELECTED;
                takeLeadership();

                break;
            case NODEEXISTS:
                state = MasterStates.NOTELECTED;
                masterExists();
                
                break;
            default:
                state = MasterStates.NOTELECTED;
                LOG.error("Something went wrong when running for master.", 
                        KeeperException.create(Code.get(rc), path));
            }
            LOG.info("I'm " + (state == MasterStates.ELECTED ? "" : "not ") + "the leader " + serverId);
        }
    };

    void masterExists() {
        zk.exists("/master", 
                masterExistsWatcher, 
                masterExistsCallback, 
                null);
    }
    
    StatCallback masterExistsCallback = new StatCallback() {
        public void processResult(int rc, String path, Object ctx, Stat stat){
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                masterExists();
                
                break;
            case OK:
                break;
            case NONODE:
                state = MasterStates.RUNNING;
                runForMaster();
                LOG.info("It sounds like the previous master is gone, " +
                    		"so let's run for master again."); 
                
                break;
            default:     
                checkMaster();
                break;
            }
        }
    };
    
    Watcher masterExistsWatcher = new Watcher(){
        public void process(WatchedEvent e) {
            if(e.getType() == EventType.NodeDeleted) {
                assert "/master".equals( e.getPath() );
                
                runForMaster();
            }
        }
    };
    
    void takeLeadership() {
        LOG.info("Going for list of workers");
        getWorkers();
        
        (new RecoveredAssignments(zk)).recover( new RecoveryCallback() {
            public void recoveryComplete(int rc, List<String> tasks) {
                if(rc == RecoveryCallback.FAILED) {
                    LOG.error("Recovery of assigned tasks failed.");
                } else {
                    LOG.info( "Assigning recovered tasks" );
                    getTasks();
                }
            }
        });
    }
    
    /*
     * Run for master. To run for master, we try to create the /master znode,
     * with masteCreateCallback being the callback implementation. 
     * In the case the create call succeeds, the client becomes the master.
     * If it receives a CONNECTIONLOSS event, then it needs to check if the 
     * znode has been created. In the case the znode exists, it needs to check
     * which server is the master.
     */
    
    /**
     * Tries to create a /master lock znode to acquire leadership.
     *
    public void runForMaster() {
        LOG.info("Running for master");
        zk.create("/master", 
                serverId.getBytes(), 
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.EPHEMERAL,
                masterCreateCallback,
                null);
    }
        
    DataCallback masterCheckCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                checkMaster();
                
                break;
            case NONODE:
                runForMaster();
                
                break; 
            case OK:
                if( serverId.equals( new String(data) ) ) {
                    state = MasterStates.ELECTED;
                    takeLeadership();
                } else {
                    state = MasterStates.NOTELECTED;
                    masterExists();
                }
                
                break;
            default:
                LOG.error("Error when reading data.", 
                        KeeperException.create(Code.get(rc), path));               
            }
        } 
    };
        
    void checkMaster() {
        zk.getData("/master", false, masterCheckCallback, null);
    }
    
    /*
     ****************************************************
     **************************************************** 
     * Methods to handle changes to the list of workers.*
     ****************************************************
     ****************************************************
     */
    
    
    /**
     * This method is here for testing purposes.
     * 
     * @return size Size of the worker list
     */
    public int getWorkersSize(){
        if(workersCache == null) {
            return 0;
        } else {
            return workersCache.getList().size();
        }
    }
    
    Watcher workersChangeWatcher = new Watcher() {
        public void process(WatchedEvent e) {
            if(e.getType() == EventType.NodeChildrenChanged) {
                assert "/workers".equals( e.getPath() );
                
                getWorkers();
            }
        }
    };
    
    void getWorkers(){
    	//the purpose of this zookeeper access is to obtain a list of our workers we can use, and set a watch to get any new workers that join
        zk.getChildren("/workers", 
                workersChangeWatcher, 
                workersGetChildrenCallback, 
                null);
    }
    
    ChildrenCallback workersGetChildrenCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getWorkers();
                break;
            case OK:
                LOG.info("Succesfully got a list of workers: " 
                        + children.size() 
                        + " workers");
                //reassignAndSet(children);
                if(workersCache == null) {
                	workersCache = new ChildrenCache(children);
                }
                else
                	workersCache.removedAndSet(children);
                break;
            default:
                LOG.error("getChildren failed",  
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    /*
     *******************
     *******************
     * Assigning tasks.*
     *******************
     *******************
     *
    
    void reassignAndSet(List<String> children){
        List<String> toProcess;
        
        if(workersCache == null) {
            workersCache = new ChildrenCache(children);
            toProcess = null;
        } else {
            LOG.info( "Removing and setting" );
            toProcess = workersCache.removedAndSet( children );
        }
        
        if(toProcess != null) {
            for(String worker : toProcess){
                getAbsentWorkerTasks(worker);
            }
        }
    }
    
    void getAbsentWorkerTasks(String worker){
        zk.getChildren("/assign/" + worker, false, workerAssignmentCallback, null);
    }
    
    ChildrenCallback workerAssignmentCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getAbsentWorkerTasks(path);
                
                break;
            case OK:
                LOG.info("Succesfully got a list of assignments: " 
                        + children.size() 
                        + " tasks");
                
                /*
                 * Reassign the tasks of the absent worker.  
                 *
                
                for(String task: children) {
                    getDataReassign(path + "/" + task, task);                    
                }
                break;
            default:
                LOG.error("getChildren failed",  KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    /*
     ************************************************
     * Recovery of tasks assigned to absent worker. * 
     ************************************************
     *
    
    /**
     * Get reassigned task data.
     * 
     * @param path Path of assigned task
     * @param task Task name excluding the path prefix
     *
    void getDataReassign(String path, String task) {
        zk.getData(path, 
                false, 
                getDataReassignCallback, 
                task);
    }
    
    /**
     * Context for recreate operation.
     *
     *
    class RecreateTaskCtx {
        String path; 
        String task;
        byte[] data;
        
        RecreateTaskCtx(String path, String task, byte[] data) {
            this.path = path;
            this.task = task;
            this.data = data;
        }
    }

    /**
     * Get task data reassign callback.
     *
    DataCallback getDataReassignCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                getDataReassign(path, (String) ctx); 
                
                break;
            case OK:
                recreateTask(new RecreateTaskCtx(path, (String) ctx, data));
                
                break;
            default:
                LOG.error("Something went wrong when getting data ",
                        KeeperException.create(Code.get(rc)));
            }
        }
    };
    
    /**
     * Recreate task znode in /tasks
     * 
     * @param ctx Recreate text context
     *
    void recreateTask(RecreateTaskCtx ctx) {
        zk.create("/tasks/" + ctx.task,
                ctx.data,
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT,
                recreateTaskCallback,
                ctx);
    }
    
    /**
     * Recreate znode callback
     *
    StringCallback recreateTaskCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                recreateTask((RecreateTaskCtx) ctx);
       
                break;
            case OK:
                deleteAssignment(((RecreateTaskCtx) ctx).path);
                
                break;
            case NODEEXISTS:
                LOG.info("Node exists already, but if it hasn't been deleted, " +
                		"then it will eventually, so we keep trying: " + path);
                recreateTask((RecreateTaskCtx) ctx);
                
                break;
            default:
                LOG.error("Something wwnt wrong when recreating task", 
                        KeeperException.create(Code.get(rc)));
            }
        }
    };
    
    /**
     * Delete assignment of absent worker
     * 
     * @param path Path of znode to be deleted
     *
    void deleteAssignment(String path){
        zk.delete(path, -1, taskDeletionCallback, null);
    }
    
    VoidCallback taskDeletionCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object rtx){
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                deleteAssignment(path);
                break;
            case OK:
                LOG.info("Task correctly deleted: " + path);
                break;
            default:
                LOG.error("Failed to delete task data" + 
                        KeeperException.create(Code.get(rc), path));
            } 
        }
    };*/
    
    /*
     ******************************************************
     ******************************************************
     * Methods for receiving new tasks and assigning them.*
     ******************************************************
     ******************************************************
     */
      
    private static RequestType getRequestType(String request) {
    	if(request.substring(0,6).toLowerCase().equals("insert"))
    		return RequestType.INSERT;
    	if(request.substring(0,6).toLowerCase().equals("delete"))
    		return RequestType.DELETE;
    	if(request.substring(0,8).toLowerCase().equals("retrieve"))
    		return RequestType.RETRIEVE;
    	if(request.substring(0, 9).toLowerCase().equals("calculate"))
    		return RequestType.CALCULATE;
    	return RequestType.ERROR;
    }
    
    
    
    Watcher tasksChangeWatcher = new Watcher() {
        public void process(WatchedEvent e) {
            if(e.getType() == EventType.NodeChildrenChanged) {
                assert "/tasks".equals( e.getPath() );
                
                getTasks();
            }
        }
    };
    
    void getTasks(){
    	//the point of this zookeeper access originally sets a watch on the tasks folder, to be aware of any new tasks by clients. however, when the
    	//watch is called, it will call this function to ensure no more changes were made from the time the watch was triggered until the time 
    	//we received the watch notification
        zk.getChildren("/tasks", 
                tasksChangeWatcher, 
                tasksGetChildrenCallback, 
                null);
    }
    /*
    	gets the list of tasks to be assigned to workers
    */
    ChildrenCallback tasksGetChildrenCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch(Code.get(rc)) { 
            case CONNECTIONLOSS:
                getTasks();
                
                break;
            case OK:
                List<String> toProcess;
                if(tasksCache == null) {
                    tasksCache = new ChildrenCache(children);
                    
                    toProcess = children;
                } else {
          
                    toProcess = tasksCache.addedAndSet( children );
                    
                }
                
                if(toProcess != null){
                	assignTasks(toProcess);
                } 
                
                break;
            default:
                LOG.error("getChildren failed.",  
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    void assignTasks(List<String> tasks) {
        for(String task : tasks){
        	
       	 
        	
        	getTaskData(task);
        	
        }
    }

    void getTaskData(String task) {
    	//this zookeeper access serves to retreive the data from the nodes that we know have changed somehow. We know becuase of the tasksChangeWatcher above
        zk.getData("/tasks/" + task, 
                false, 
                taskDataCallback, 
                task); //THIS IS THE CTX YOUVE BEEN LOKKING FOR
    }
    
    DataCallback taskDataCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
            switch(Code.get(rc)) { 
            case CONNECTIONLOSS:
                getTaskData((String) ctx);
                
                break;
            case OK:
            	
            	
            	determineRequest(data, ctx);
            	
            	
            	
            	
                
                break;
            default:
                LOG.error("Error when trying to get task data.", 
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    
    /*
     * Determines the type of request and decided what to do with it
     */
    void determineRequest(byte[] data, Object ctx) {
    	
    	if(workersCache == null) {
    		workersCache = new ChildrenCache();
    	}
    	List<String> list = workersCache.getList();
    	
    	String request = new String(data);
        
        int firstSpace = request.indexOf(" "), secondSpace;
        String key, designatedWorker, assignmentPath;
        
        RequestType type = getRequestType(request);
        
    	switch(type) {
    	case INSERT:
    		
    		secondSpace = request.lastIndexOf(" ");
    		key = request.substring(firstSpace+1, secondSpace);
    		
    		
    		
    		
    		if(workersCache.getWorker(key) != null)
    			designatedWorker = workersCache.getWorker(key);
    		else {
    			designatedWorker = list.get(random.nextInt(list.size()));
    			workersCache.associate(key, designatedWorker);
    		}
    		
    		assignmentPath = "/assign/" + 
                    designatedWorker + 
                    "/" + 
                    (String) ctx;
            LOG.info( "Insert request recieved. Assignment path: " + assignmentPath );
            data = ((String) ctx + " " + request).getBytes();
            createAssignment(assignmentPath, data);
    		
    		
        
    		break;
    		
    	case DELETE:
    	case RETRIEVE:
    		
    		key = request.substring(firstSpace + 1);
    		designatedWorker = workersCache.getWorker(key);
    		
    		assignmentPath = "/assign/" + 
                    designatedWorker + 
                    "/" + 
                    (String) ctx;
    		if(type == RequestType.DELETE) 
    			LOG.info( "Delete Request Recieved. Assignment path: " + assignmentPath );
    		else
    			LOG.info( "Retrieve request recieved. Assignment path: " + assignmentPath );
    		data = ((String) ctx + " " + request).getBytes();
            createAssignment(assignmentPath, data);
            
            
    		break;
    		
    	case CALCULATE: //the most complicated type of request: the request needs to be split up into multiple parts, each part to be sent to different worker, depending on multiple things
    		secondSpace = request.indexOf(" ", firstSpace + 1);
    		String operator = request.substring(firstSpace+1, secondSpace);
    		String operands = request.substring(secondSpace + 1);
    		String[] opList = operands.split(" ");
    		
    		ArrayList<ArrayList<String>> similars = new ArrayList<ArrayList<String>>(); 
    		similars.add(new ArrayList<String>());
    		int currentL = 0;
    		int current = 1;
    		similars.get(0).add(opList[0]);
    		String previousWorker = workersCache.getWorker(similars.get(0).get(0));
    		String currentWorker;
    		
    		for(int i = 1; i<opList.length; i++) {//at the end of this loop, similars will contain lists grouping all operand keys contained in same workers
    			currentWorker = workersCache.getWorker(opList[i]);
    			
    			if(previousWorker.equals(currentWorker)) //the worker which contained the previous value also contains this value, add to previous list
    				similars.get(currentL).add(opList[i]);
    			else {//they are contained in different workers, start next list
    				currentL++;
    				similars.add(new ArrayList<String>());
    				similars.get(currentL).add(opList[i]);
    				previousWorker = currentWorker;
    			}
    			
    			
    		}
    		
    		tasksCache.associateTask((String) ctx, request, currentL + 1);//create an association from the task name to an object which will hold solutions until they are ready to be combined
    		
    		
    		createInitialFolder("/completed/" + (String) ctx, "parts".getBytes());//create the node to store eventual answers to parts
			
    		
    		String assignmentData;
    		
    		int counter = 1;
    		for(ArrayList<String> A : similars) {//assign all parts of the request to corresponding workers, with added counter for order, like TCP
    			assignmentData = (String) ctx + " calculate " + counter + " " + operator + " " + String.join(" ", A);
    			designatedWorker = workersCache.getWorker(A.get(0));
    			assignmentPath = "/assign/" + 
                        designatedWorker + 
                        "/" + 
                        (String) ctx + "part" + counter;
    			createAssignment(assignmentPath, assignmentData.getBytes());
    			LOG.info("partial calculation " + assignmentData + " has been assigned to worker " + designatedWorker);
    			counter++;
    		}
    		
    		deleteTask((String) ctx);
    			
    		
    		
    		break;
    	case ERROR:
    		
    		LOG.info("Error: Request is incorrectly formatted");
    		
    		break;
    	}
    }
    
    void createInitialFolder(String path, byte[] data) {
    	//this zookeeper access initiallizes the folder in the completed folder into which this assignment will store its multiple parts.
		//usually this will be created when the assignment is completed, but since calculate requests require multiple parts, it is better to
		//create the parent of the folder into which we will create the parts later, to avoid confusion.
    	zk.create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, createInitialCallback, data);
    }
    
    StringCallback createInitialCallback = new StringCallback() {

		@Override
		public void processResult(int rc, String path, Object ctx, String name) {
			switch(Code.get(rc)) {
			case CONNECTIONLOSS:
				createInitialFolder(path, (byte[]) ctx);
				break;
			case OK:
				LOG.info("successfully created storage node for completed parts: " + path);
				break;
			default:
				LOG.info("Node parts folder already created");
			}

		}
    	
    };
    
    void createAssignment(String path, byte[] data){
    	//Does what is says: creates an "assignment", or a node in a workers assignemnet folder, containing a task for the worker to perform
        zk.create(path, 
                data, 
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT,
                assignTaskCallback, 
                data);
       
    }
    
    StringCallback assignTaskCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch(Code.get(rc)) { 
            case CONNECTIONLOSS:
                createAssignment(path, (byte[]) ctx);
                
                break;
            case OK:
                LOG.info("Task assigned correctly: " + name);
                
                if(!(new String((byte[]) ctx)).startsWith("calculate"))
                	
                	deleteTask(name.substring( name.lastIndexOf("/") + 1)); 
               
                

                
                
                break;
            case NODEEXISTS: 
                LOG.warn("Task at " + path + " already assigned");
                
                break;
            default:
                LOG.error("Error when trying to assign task.", 
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    /*
     * Once assigned, we delete the task from /tasks
     */
    void deleteTask(String name){
    	//we no longer need the task that the client posted, since we already posted it to the worker. Delete it
        zk.delete("/tasks/" + name, -1, taskDeleteCallback, null);
    }
    
    Watcher taskResultWatcher = new Watcher() {
        public void process(WatchedEvent e) {
        	if(e.getType() == EventType.NodeChildrenChanged) {
                
                getResultChildren(e.getPath());
            }
        }
    };
    
    void getResultChildren(String path) {
    	//this zk access gets the completed tasks from the workers to be processed. These could be partial tasks which need to be combined into a total task later on
    	zk.getChildren(path, taskResultWatcher, resultChildrenCallback, null);
    }
    
    ChildrenCallback resultChildrenCallback = new ChildrenCallback() {

		public void processResult(int rc, String path, Object ctx, List<String> children) {
			switch(Code.get(rc)) { 
            case CONNECTIONLOSS:
                getResultChildren(path);
                
                break;
            case OK:
               for(String task : children) {
            	   returnResult(path + "/" + task, task);
               }
                
                break;
            case NONODE:
            	LOG.info("node " + path + " deleted successfully");
            	break;
            
            default:
                LOG.error("getChildren failed.",  
                        KeeperException.create(Code.get(rc), path));
                
            }
			
		}
    	
    };
    
    void returnResult(String path, String task) {
    	//this zk access gets the result data from tasks completed by the workers
    	zk.getData(path, false, resultCallback, task);;
    }
    /*
    Contains all the logic for processing completed requests, or completed partial requests from calculate tasks.
    */
    DataCallback resultCallback = new DataCallback() {
		public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
			switch(Code.get(rc)) {
			case CONNECTIONLOSS:
	            returnResult(path, (String)ctx);
	            
	            break;
	        case OK:
	        	boolean completedAllParts = true;//assume this is a whole completed task that can be returned to the client now
	        	String result = new String(data);
	        	int firstSpace = result.indexOf(" ");
	        	if(result.substring(0, 5).equals("parts"))//this is a multi-part answer that needs to be combined, 
								  //must go into this folder to access its individual children-partsrts
	        		getResultChildren(path);
	        	else {
	        		if(result.length() > firstSpace + 11 && result.substring(firstSpace+1,firstSpace + 10).equals("calculate")) {//this is one part of a multi part answer
		        		completedAllParts = false;//may not be a complete answer
	        			LOG.info("Part of calculate request completed");
		        		String taskName = result.substring(0, firstSpace);
		        		
		        		LOG.info("Partial solution " + result + " has been completed");
					
					//add this partially completed task to tasksCache, where it will be ordered based on its ordered number and stored until all parts have been completed and returned 
					//to master
		        		completedAllParts = tasksCache.addCaclulationPart(taskName, result.substring(firstSpace+1));
		        		
		        		//delete the partial task, as it has been stored
		        		deleteFulfilledRequest(path, taskName);
		        		
		        		
		        		if(completedAllParts) { //all the parts of this task have now been completed, need to combine them and send the result to the client
		        			result = processCompletedCalculate(taskName);
		        			
		        		}
		        		
	        		}
	        	
	        		
	        		if(completedAllParts) {//this is a full answer, ie, resulting from a put, get, delete, or already combined parts of a calculation answer
				        int lastSpace = result.lastIndexOf(" ");
				        String taskName = result.substring(0, firstSpace);
				        String task = result.substring(firstSpace+1, lastSpace);
				        if(result.toLowerCase().contains("error")) {//this request encountered an error somewhere along the way
				            	LOG.info("Task " + taskName + " has encountered an error: " + result.substring(firstSpace+1));
				            	createPostToClient("/status/" + taskName, result.substring(firstSpace+1).getBytes());
				            	deleteFulfilledRequest("/completed/" + taskName, taskName);
				        }
				        else {//this request was error free
				        	
				            String solution = result.substring(lastSpace+1);
				            
				            
				            LOG.info("Task " + task  + " has been resolved. Solution: " + task + ". Result: " + solution);
				            
					    //create a node which gives the client the answer
					    createPostToClient("/status/" + taskName, (task +  ". Result: " + solution).getBytes());
								
							
				            
				            LOG.info("Task " + task + " solution has been posted to Client");
				            
					    //delete the result node from the worker process
				            deleteFulfilledRequest("/completed/" + taskName, taskName);
				            
				            //LOG.info("znode " + (String) ctx + " successfully deleted, as worker has completed it");
	        			}
	        		}
	        	}
	            break;
	        case NONODE:
	        	LOG.info("task at " + path + " has already been dealt with");
	        	break;
	        default:
	            LOG.error("getChildren failed.",  
	                    KeeperException.create(Code.get(rc), path));
	        }
		}
    };
    
    void deleteFulfilledRequest(String path, String taskName) {
	//the point of this zk access is to delete the result node that the worker sent to the master, whose solution has already been sent to client.
    	zk.delete(path, -1, deleteFulfilledCallback, taskName);
    }
    
    VoidCallback deleteFulfilledCallback = new VoidCallback() {

		@Override
		public void processResult(int rc, String path, Object ctx) {
			switch(Code.get(rc)) {
			case CONNECTIONLOSS:
				deleteFulfilledRequest(path, (String) ctx);
				break;
			case OK:
				LOG.info("successfully deleted completed task: " + (String) ctx);
				break;
			default:
				LOG.info("Node " + (String) ctx + " has already been deleted");
			
			}
			
		}
    	
    };
    
    void createPostToClient(String path, byte[] data) {
	//this creates the node which the client is listening for, contains the solution to a client's request
    	zk.create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, taskCreateCallback, data);
    }
    
    StringCallback taskCreateCallback = new StringCallback() {

		@Override
		public void processResult(int rc, String path, Object ctx, String name) {
			switch (Code.get(rc)) {
            case CONNECTIONLOSS:
               createPostToClient(path, (byte[]) ctx);
                
                break;
            case OK:
                LOG.info("Successfully created " + path);
                
                break;
            case NODEEXISTS:
                LOG.info("Answer has been created already");
                
                break;
            default:
                LOG.error("Something went wrong here, " + 
                        KeeperException.create(Code.get(rc), path));
            }
			
		}

		

    };
    
    
    
    VoidCallback taskDeleteCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object ctx){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                deleteTask(path);
                
                break;
            case OK:
                LOG.info("Successfully deleted " + path);
                
                break;
            case NONODE:
                LOG.info("Task at " + path + " has been deleted already");
                
                break;
            default:
                LOG.error("Something went wrong here, " + 
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };
    /**
	
	
    */
    String processCompletedCalculate(String taskName) {
    	String[] tasks = tasksCache.getCalculationParts(taskName);
    	if(tasks[0].toLowerCase().contains("error")) {
    		return taskName + " " + tasks[0];
    	}
    	
    	String operator = tasks[0].substring(12, 13);
    	
    	String combinedValues = tasks[0].substring(14, tasks[0].lastIndexOf(" ")); //initialize to first set of values
    	
    	BigDecimal combinedSolutions = new BigDecimal(tasks[0].substring(tasks[0].lastIndexOf(" ") + 1));
    	
    	String values, current;
    	int thirdSpace, lastSpace;
    	BigDecimal solution = new BigDecimal(0);
    	
    	for(int i=1; i<tasks.length; i++) {
    		current = tasks[i];
    		
    		
    		if(current.toLowerCase().contains("error"))//there was an error in one of the parts, return just an error message
    			return taskName + " " + current;
    		
    		
    		thirdSpace = tasks[i].indexOf(" ", 10) + 2;
    		lastSpace = current.lastIndexOf(" ");
    		if((operator.equals("/") || operator.equals("-"))) {
    			values = current.substring(thirdSpace+1);
    			combinedValues += " " + values;
    			String[] valueList = values.split(" ");
    			for(String value : valueList) {
    				combinedSolutions = performOp(operator, combinedSolutions, new BigDecimal(value));
    			}
    		}
    		else {
    			values = current.substring(thirdSpace + 1, lastSpace);
    			combinedValues += " " + values;

    			
    			solution = new BigDecimal(current.substring(lastSpace + 1));
    			
    			
    			combinedSolutions = performOp(operator, combinedSolutions, solution);
    		}
    	}
    	
    	String requestTotal = taskName + " calculate " + operator + " " + combinedValues  + " " +  combinedSolutions; 
    	
    	return requestTotal;
    	
    	
    	
    }
    
    BigDecimal performOp(String operator, BigDecimal a, BigDecimal b) {
    	BigDecimal solution = new BigDecimal(0);
		
		switch (operator) {
		case("+"):
			solution = a.add(b);
			break;
		
		case("-"):
			solution = a.subtract(b);
			break;
		
		case("*"):
			solution = a.multiply(b);
			break;
		
		case("/"):
			if(b.doubleValue() == 0)
				solution = new BigDecimal(0);
			else
				solution = a.divide(b,2, BigDecimal.ROUND_HALF_UP);
			break;
		}
		
		return solution;
    }
    
    /**
     * Closes the ZooKeeper session. 
     * 
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        if(zk != null) {
            try{
                zk.close();
            } catch (InterruptedException e) {
                LOG.warn( "Interrupted while closing ZooKeeper session.", e );
            }
        }
    }
    
    /**
     * Main method providing an example of how to run the master.
     * 
     * @param args
     * @throws Exception
     */
    public static void main(String args[]) throws Exception { 
        Master m = new Master(args[0]);
        
        m.startZK();
        
        while(!m.isConnected()){
            Thread.sleep(100);
        }
        /*
         * bootstrap() creates some necessary znodes.
         */
        m.bootstrap();
        m.getWorkers();
        m.getTasks();
        m.getResultChildren("/completed");
        /*
         * now runs for master.
         */
        //m.runForMaster();
        
        while(!m.isExpired()){
            Thread.sleep(1000);
        }   

        m.stopZK();
        m.close();
    }
}
