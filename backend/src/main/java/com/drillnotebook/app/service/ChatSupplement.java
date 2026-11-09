package com.drillnotebook.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Received human input remains distinct from both the original question and the assistant's answer. */
public record ChatSupplement(String requestId, String userText, int replyOffset, List<String> attachments) {
    private static final ObjectMapper JSON=new ObjectMapper();

    public static List<ChatSupplement> read(Connection connection,String parent,ChatMessageCipher cipher,String password) throws SQLException {
        if(parent==null)return List.of();
        var result=new ArrayList<ChatSupplement>();
        try(var statement=connection.prepareStatement("""
                SELECT p.request_id,p.reply_offset,p.payload_cipher,p.payload_meta FROM ai_chat_pending p
                JOIN ai_session_dispatch d ON d.request_id=p.request_id
                JOIN ai_chat_turn t ON t.request_id=p.parent_request_id
                WHERE p.parent_request_id=? AND p.state='RECEIVED' AND t.content_deleted_at IS NULL ORDER BY d.sequence
                """)) {
            statement.setString(1,parent);
            try(var rows=statement.executeQuery()){
                while(rows.next()){
                    var value=cipher.read(rows.getString(3),rows.getString(4),password);
                    if(value.status()!=ChatMessageCipher.ReadStatus.DECRYPTED)throw new ChatCryptoException("插话内容无法解密");
                    try{
                        var input=JSON.readTree(value.value()).path("body").path("input");
                        var names=new ArrayList<String>();
                        for(var attachment:input.path("attachments"))names.add(attachment.path("name").asText("附件"));
                        result.add(new ChatSupplement(rows.getString(1),input.path("userText").asText(),rows.getInt(2),List.copyOf(names)));
                    }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ChatCryptoException("插话内容无法读取");}
                }
            }
        }
        return List.copyOf(result);
    }
    public Map<String,Object> view(){return Map.of("requestId",requestId,"userText",userText,"replyOffset",replyOffset,"state","RECEIVED","attachments",attachments);}
    public String contextText(){return userText+(attachments.isEmpty()?"":"\n[附件："+String.join("、",attachments)+"；历史不重放附件内容]");}

    /** Split the accumulated answer at the point at which each supplemental input was accepted. */
    public static List<Map<String,Object>> messages(String answer,List<ChatSupplement> supplements){
        var messages=new ArrayList<Map<String,Object>>();int offset=0;
        for(var item:supplements){
            int end=Math.min(answer.length(),Math.max(offset,item.replyOffset()));
            if(end>offset)messages.add(Map.of("role","assistant","content",answer.substring(offset,end)));
            messages.add(Map.of("role","user","content",item.contextText()));offset=end;
        }
        if(offset<answer.length()||supplements.isEmpty())messages.add(Map.of("role","assistant","content",answer.substring(offset)));
        return messages;
    }
    public static String transcript(String answer,List<ChatSupplement> supplements){
        if(supplements.isEmpty())return answer;
        var text=new StringBuilder();
        for(var message:messages(answer,supplements))text.append("\n["+ ("user".equals(message.get("role"))?"用户插话":"助手")+"]\n").append(message.get("content"));
        return text.toString();
    }
}
