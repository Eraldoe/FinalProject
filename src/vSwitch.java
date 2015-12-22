import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openflow.io.OFMessageAsyncStream;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.protocol.factory.BasicFactory;
import org.openflow.util.LRULinkedHashMap;


public class vSwitch implements Runnable{
	private Map<Integer, Short> macTable = new LRULinkedHashMap<Integer, Short>(64001, 64000);
	private String threadName;
	private OFMessageAsyncStream stream;
	private BasicFactory factory = new BasicFactory();
	private List<OFMessage> l = new ArrayList<OFMessage>();
	private List<OFMessage> msgIn = new ArrayList<OFMessage>();
	private PacketHandler pkhl;
	private SocketChannel sock;
	private Thread t;
	
	
	public vSwitch(String name, OFMessageAsyncStream strm, SocketChannel s) {
		threadName = name;
		stream = strm;
		sock = s;
	}
	
	/**
	 * Attempts to flush all messages waiting to be sent to the switch
	 */
	public synchronized void flush(){
		while(stream.needsFlush()){
        	try {
				stream.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
	}
	
	
	protected synchronized void sendMsgs(OFMessage msg){
		try {
			stream.write(msg);
			while(stream.needsFlush()){
        		stream.flush();
        	}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}

	private void abort(){
		stop();
		pkhl.stop();
		pkhl=null;
		try {
			sock.close();
		} catch (IOException e) {

		}
	}
	
	@Override
	public void run(){
		pkhl = new PacketHandler(threadName + "_PacketHandler",stream,macTable,this); 
		pkhl.start();
		
		l.add(factory.getMessage(OFType.HELLO));
        l.add(factory.getMessage(OFType.FEATURES_REQUEST));
        try {
        	long lastHeard = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        	stream.write(l);
        	flush();
        	l.clear();
        	
        	//strip all the HELLOs we've heard so we can start fresh
        	msgIn.addAll(stream.read());
        	for(int i = 0; i < msgIn.size(); i++){
        		if(msgIn.get(i).getType()==OFType.HELLO){
        			msgIn.remove(i);
        		}
        	}
        	
        	boolean waitForReply = false;
        	
        	OFMessage msg = null;
            while(t.isInterrupted()==false){
            	
            	try{
            		msgIn.addAll(stream.read());
            		Thread.sleep(0, 1);
            	}catch(NullPointerException e){
            		abort();
            		return;
            	}
            	
            	if(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - lastHeard > 4 && waitForReply==false){
            		l.add(factory.getMessage(OFType.ECHO_REQUEST));
			        stream.write(l);
			        flush();
				    l.clear();
				    waitForReply = true;
            	}
            	
            	if(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - lastHeard > 10){ //switch timed out. delete the connection and make it start from scratch
            		abort();
            		return;
            	}
            	
    	        if(!(msgIn.size()==0)){
	    			msg = msgIn.remove(0);
	    			if(msg.getType() == OFType.ECHO_REQUEST){
	    				lastHeard = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    		    		l.add(factory.getMessage(OFType.ECHO_REPLY));
    			        stream.write(l);
    			        flush();
    				    l.clear();
    				    waitForReply = false;
    		    	}
	    			else if(msg.getType() == OFType.ECHO_REPLY){
	    				lastHeard = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
	    				waitForReply = false;
    		    	}
	    			else {
	    				pkhl.addPacket(msg);
	    				pkhl.wakeUp();
	    			}
    	        }
            }
        	
        	
        	
		} catch (Exception e) {
			abort();
			e.printStackTrace();
			return;
		}
        
        pkhl.stop();
        this.stop();
        
        
	}
	
	
	public void stop(){
		t.interrupt();
		System.out.println("Stopping " +  threadName);
		pkhl.stop();
	}
	
	public void start (){
      System.out.println("Starting " +  threadName);
      if (t == null){
         t = new Thread (this, threadName);
         t.start ();
      }
   }
}
