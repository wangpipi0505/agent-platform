package cn.agentplatform.gateway.infra;

/** Resource admission only; Owner conveys no IAM authority. Conservative charges, not heap telemetry. */
public final class PortalEventsPacketBudget {
    public static final long GLOBAL_BYTES=201326592L,OWNER_BYTES=100663296L,SCAN_BYTES=67108864L;
    public static final PortalEventsPacketBudget SHARED=new PortalEventsPacketBudget();
    private static final long OWNER_BASE=65536L,TOKEN_BYTES=128L;
    private long reserved;
    private int owners;
    private boolean connectorPrepaid;
    private PortalEventsPacketBudget(){}
    /** One fixed adapter graph per JVM; never an Owner or an IAM capability. */
    synchronized void reserveConnectorBaseline(){
        if(connectorPrepaid)return;
        long next=Math.addExact(reserved,1048576L);
        if(next>GLOBAL_BYTES)throw new IllegalStateException("connector baseline exhausted");
        reserved=next;connectorPrepaid=true;
    }
    synchronized long connectorBaselineBytes(){return connectorPrepaid?1048576L:0L;}
    public synchronized Owner acquire(){
        if(owners==2)throw new IllegalStateException("packet owner slots exhausted");
        long next=Math.addExact(reserved,OWNER_BASE);
        if(next>GLOBAL_BYTES)throw new IllegalStateException("packet global budget exhausted");
        reserved=next;owners++;
        try{return new Owner(this);}catch(RuntimeException|Error e){reserved-=OWNER_BASE;owners--;throw e;}
    }
    public synchronized long reservedBytes(){return reserved;}
    public synchronized int owners(){return owners;}
    public static final class Owner implements AutoCloseable {
        private final PortalEventsPacketBudget budget;
        private long reserved=OWNER_BASE;
        private boolean closed,claimed;
        private PortalEventsPacketDecoder decoder;
        private Owner(PortalEventsPacketBudget budget){this.budget=budget;}
        synchronized void claim(PortalEventsPacketDecoder candidate){
            requireOpen();if(claimed)throw new IllegalStateException("owner already claimed a decoder");claimed=true;decoder=candidate;
        }
        synchronized void requireOpen(){if(closed)throw new IllegalStateException("packet owner closed");}
        public synchronized long reservedBytes(){return reserved;}
        /** F2 must reserve its additional business model/frame before allocating it. */
        public synchronized Reservation reserve(long bytes){
            requireOpen();if(bytes<0)throw new IllegalArgumentException("negative reservation");
            long charged=Math.addExact(bytes,TOKEN_BYTES),next=Math.addExact(reserved,charged);
            synchronized(budget){long global=Math.addExact(budget.reserved,charged);if(next>OWNER_BYTES||global>GLOBAL_BYTES)throw new IllegalStateException("packet allocation budget exhausted");reserved=next;budget.reserved=global;}
            try{return new Reservation(this,charged);}catch(RuntimeException|Error e){release(charged);throw e;}
        }
        private synchronized void release(long bytes){
            if(bytes>reserved)throw new IllegalStateException("packet ledger underflow");synchronized(budget){reserved-=bytes;budget.reserved-=bytes;}
        }
        @Override public synchronized void close(){
            if(closed)return;
            if(decoder!=null)decoder.close(); // Refuse while physical read/borrow is active; caller retries after actual exit.
            if(reserved!=OWNER_BASE)throw new IllegalStateException("owner has live reservations");
            decoder=null;closed=true;synchronized(budget){budget.reserved-=OWNER_BASE;reserved=0;budget.owners--;}
        }
    }
    /** Resource tokens cannot be minted by callers. */
    public static final class Reservation implements AutoCloseable {
        private Owner owner;
        private final long bytes;
        private Reservation(Owner owner,long bytes){this.owner=owner;this.bytes=bytes;}
        @Override public synchronized void close(){if(owner!=null){owner.release(bytes);owner=null;}}
    }
}
