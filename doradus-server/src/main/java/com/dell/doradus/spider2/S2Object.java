package com.dell.doradus.spider2;

import com.dell.doradus.common.Utils;
import com.dell.doradus.core.IDGenerator;
import com.dell.doradus.spider2.fastjson.JsonNode;
import com.dell.doradus.spider2.jsonbuild.JMapNode;
import com.dell.doradus.spider2.jsonbuild.JNode;

public class S2Object implements Comparable<S2Object> {
    private String m_id;
    private byte[] m_data;
   
    public static S2Object read(MemoryStream stream) {
        String id = stream.readString();
        int length = stream.readVInt();
        byte[] data = new byte[length];
        stream.read(data, 0, length);
        return new S2Object(id, data);
    }
    
    private S2Object(String id, byte[] data) {
        m_id = id;
        m_data = data;
    }
    
    public S2Object(JMapNode data) {
        m_id = data.getString("_id");
        if(m_id == null) {
            m_id = Utils.base64FromBinary(IDGenerator.nextID());
            data.addString("_id", m_id);
        }
        m_data = data.getBytes();
    }
    
    public void write(MemoryStream stream) {
        stream.writeString(m_id);
        stream.writeVInt(m_data.length);
        stream.write(m_data, 0, m_data.length);
    }
    
    
    public String getId() { return m_id; }
    public JMapNode getJNode() { return (JMapNode)JNode.fromBytes(m_data); }
    public JsonNode getJsonNode() { return new JsonNode(m_data); }

    @Override public int hashCode() { return m_id.hashCode(); }
    @Override public boolean equals(Object obj) { return m_id.equals(((S2Object)obj).m_id); }
    @Override public String toString() { return getJNode().getString(true); }
    @Override public int compareTo(S2Object o) { return m_id.compareTo(o.m_id); }
}
