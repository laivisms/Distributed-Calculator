The Distributed Calculator application. Implements a distributed system which performs the function of a calculator, 
          using a master-worker structure and zookeeper for coordination
                
               API:
                  INSERT [KEY] [VALUE]                                - Stores VALUE under KEY in the system. Value must be a number
                  DELETE [KEY]                                        - Deletes KEY and its associated value from the system
                  RETRIEVE [KEY]                                      - Returns the value associated with KEY
                  CALCULATE [OPERATOR] [KEY1] [KEY2] [KEY3]...[KEYn]  - Performs the mathematical OPERATOR on the KEYs in order
                  
               Starting the program using a input file:
                  navigate to directory in terminal, then type:
                      ./start.sh [host:port of Zookeeper server] [# of workers to start] [filepath of input file]
                      
               -Input file is formatted as API requests, separated by newlines. An example is included, named input.txt
               -Included zookeeper server is started on localhost:3001, so use that host:port combo to start this bash file
                  file if using included zookeeper server
               
               *WARNINGS*:
               
                     -Do not run this program multiple times without killing all the started processes after each run.
                          could cause unexpected problems
                     -Dividing by zero returns zero
                     -Must initialize with at least one worker
                     -Bash file is designed to be run with an empty zookeeper server. If server already contains nodes(even
                      nodes a previos Master created), unexpected errors may occur

               
               
               
               The structure of the zookeeper folders is:
                    /
                     ./workers          //stores ephemeral nodes of workers. watched by master
                        /worker-kjh3r
                        /worker-sekfes
                     ./assign           //stores the workers assignment folders, which contain tasks for workers to complete
                        ./worker-kjh3r  //stores an individual workers assigned tasks. watched by worker
                            ./task-000000003
                            ./task-000000004
                     ./completed        //stores tasks that the workers completed. Watched by master
                        ./task-000000005
                     ./tasks            //stores tasks from the client. watched by the master
                     ./status           // stores tasks completed by the master for the client to see. watched by client
                     
                The basic functioning of the program is:
                  -The client creates a task node in the tasks folder
                  -The Master picks it up and decides which worker to send it to:
                        -if INSERT task, get a random worker
                        -if DELETE/RETRIEVE task, get the worker that has the requested key
                        -if CALCULATE task, split the task up into subtasks of 1 or more keys each to be sent to the workers 
                          which contain those keys
                  -The Master send the tasks to the determined workers by posting them in the workers folder under ./assign
                  -The Worker sends the task to its WorkerLogicHandler, storing and retrieves/stores information in its WorkerData
                    object as needed.
                  -The Worker sends the result to the Master by creating a node named the task's name under ./completed, 
                    with the answer as the nodes data
                  -The Master picks up the new node and determines whether it can send it to the client yet
                      -if it is only a partial result(a calculation request is split up, need all of them back to complete request)
                          then store the result to be combined with other partial results when they all come in
                      -if it is a partial result, but it is the final partial result, combine all partial results and
                        prepare to be sent to client
                  -The master posts the answer as a node named the task's number, under the ./status folder, with the answer as the
                    nodes data
                      -The answer is the request of the client repeated, except all keys are translated to values, and 
                        "Result: " + result is appended to the end
                  
