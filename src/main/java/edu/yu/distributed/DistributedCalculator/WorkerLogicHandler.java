package edu.yu.distributed.DistributedCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WorkerLogicHandler {
	
	private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
	
	static final String BAD_REQUEST = "Error: Request incorrectly formatted.";
	static final String KEY_NOT_EXIST = "Error: the specified key does not exist 0";
	static final String INSERT = "insert";
	static final String RETRIEVE = "retrieve";
	static final String DELETE = "delete";
	static final String CALCULATE = "calculate";
	static final String VALID_TEXT = "(\\p{Alpha})+";
	static final String VALID_VALUE = "(\\d)+";
	;
	
	public static String handleRequest(String workerName, String request, WorkerData data) {
		
		
		
		if(request.length() < 6)
			return BAD_REQUEST;
		else if(request.substring(0,6).toLowerCase().equals(INSERT))
			return handlePut(workerName, request, data);
		
		else if(request.length()>8 && request.substring(0,8).toLowerCase().equals(RETRIEVE)) 
			return handleGet(workerName, request, data);
		
		else if(request.substring(0,6).toLowerCase().equals(DELETE))
			return handleDelete(workerName, request, data);
		
		else if(request.length()>9 && request.substring(0,9).toLowerCase().equals(CALCULATE))
			return handleCalculate(workerName, request, data);
		return BAD_REQUEST;
	}
	
	
	private static String handlePut(String workerName, String request, WorkerData data) {
		String result = null;
		
		int firstSpace = request.indexOf(' ');
		int lastSpace = request.lastIndexOf(' ');
		String key = request.substring(firstSpace + 1, lastSpace);
		BigDecimal value = new BigDecimal(request.substring(lastSpace+1));
		data.put(key, value);
		result = request + " " + String.valueOf(value);
			
		LOG.info("Worker " + workerName + " now contains " + value + " under key: " + key);
		
		 
		return result;
	}
	
	private static String handleGet(String workerName, String request, WorkerData data) {
		String result = null;
		String key = null;
		BigDecimal value = null;
		
		
		
		int space = request.indexOf(" ");
		key = request.substring(space+1);
		if(!data.contains(key))
			result = KEY_NOT_EXIST;
		else {
			value = data.get(key);
			result = request + " " + String.valueOf(value);
		}
		
		
		LOG.info("Worker " + workerName + " has accessed key " + key + " containing value: " + value);
		
		return result;
	}
	
	private static String handleDelete(String workerName, String request, WorkerData data) {
		String result = null;
		String key = null;
		BigDecimal value = new BigDecimal(0);
		
		
			int space = request.indexOf(" ");
			key = request.substring(space+1);
			
			if(data.contains(key)) {
				value = data.get(key);
				data.remove(key);
			}
			result = request + " " + String.valueOf(value);
		
		
		LOG.info("Worker " + workerName + " has deleted key " + key + " containing value: " + value);
		
		return result;
	}
	
	private static String handleCalculate(String workerName, String request, WorkerData data) {
		String result = null;
		
		int firstSpace = request.indexOf(" ");
		int secondSpace = request.indexOf(" ", firstSpace+1);
		
		String orderNumber = request.substring(firstSpace + 1, secondSpace);
		String operator = request.substring(secondSpace+1,secondSpace+2);
		String operands = request.substring(secondSpace+3);
		String[] operandList = operands.split(" ");
		
		String opValues = data.get(operandList[0]) + "";
		
		BigDecimal solution = data.get(operandList[0]);
		
		if(solution == null) {
			return "calculate " + orderNumber + " ERROR: provided key does not exist";
		}
		
		BigDecimal value;
		String op;
		
		for(int i=1; i<operandList.length; i++) {
			op = operandList[i];
			value = data.get(op);
			if(value == null) {
				return "calculate " + orderNumber + " ERROR: provided key does not exist";
			}
			opValues += " " + value;
			if((!operator.equals("/") && !operator.equals("-")) || orderNumber.equals("1"))//cannot compute / or - operations (because pemdas) unless they are the first ones in the list
				solution = performOp(operator, solution, value);
		}
		if((!operator.equals("/") && !operator.equals("-")) || orderNumber.equals("1"))
			result = "calculate " + orderNumber + " " + operator + " " + opValues + " " + solution;
		else
			result = "calculate " + orderNumber + " " + operator + " " + opValues;
		
		return result;
	}
	
	private static BigDecimal performOp(String operator, BigDecimal a, BigDecimal b) {
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
	
	
	
	
}
