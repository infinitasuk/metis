package com.metis.engine.core.rules.runtime;

import java.nio.ByteBuffer;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.require;

/** Server-only immutable stream checkpoint. API clients never supply or receive this. */
public record RandomStream(String algorithm,String seed,long position,List<Long> tape) {
    public RandomStream {
        Objects.requireNonNull(algorithm);Objects.requireNonNull(seed);tape=List.copyOf(tape);
        if(position<0 || !Set.of("hmac-sha256-v1","tape-v1").contains(algorithm))throw new IllegalArgumentException("Invalid random stream");
        if(algorithm.equals("hmac-sha256-v1")&&(!seed.matches("[0-9a-f]{64}")||!tape.isEmpty()))throw new IllegalArgumentException("Invalid random seed");
        if(algorithm.equals("tape-v1")&&(!seed.isEmpty()||tape.stream().anyMatch(n->n<0)))throw new IllegalArgumentException("Invalid random tape");
    }
    public static RandomStream secure() { byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return seeded(HexFormat.of().formatHex(key)); }
    public static RandomStream seeded(String hex) { return new RandomStream("hmac-sha256-v1",hex,0,List.of()); }
    public static RandomStream tape(long... values) { return new RandomStream("tape-v1","",0,Arrays.stream(values).boxed().toList()); }
    public record Draw(int ticket,RandomStream next) { }
    public Draw draw(int bound) {
        require(bound>0,"RANDOM_BOUND","","Random bound must be positive");
        if(algorithm.equals("tape-v1")) {
            require(position<tape.size(),"RANDOM_TAPE_EXHAUSTED","","Deterministic random tape is exhausted");
            long value=tape.get((int)position);require(value<bound,"RANDOM_TAPE_BOUND","","Tape value is outside requested bound");
            return new Draw((int)value,new RandomStream(algorithm,seed,Math.incrementExact(position),tape));
        }
        long cursor=position;
        try {
            var hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(HexFormat.of().parseHex(seed),"HmacSHA256"));
            for(int attempts=0;attempts<128;attempts++) {
                long bits=ByteBuffer.wrap(hmac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(cursor).array())).getLong()&Long.MAX_VALUE;
                cursor=Math.incrementExact(cursor);long value=bits%bound;
                if(bits-value+(bound-1)>=0)return new Draw((int)value,new RandomStream(algorithm,seed,cursor,List.of()));
            }
            throw new com.metis.engine.core.rules.RuleEvaluationException("RANDOM_REJECTION_LIMIT","/mechanics/random","Random sampling work limit exceeded");
        }catch(java.security.GeneralSecurityException error){throw new IllegalStateException("HMAC implementation unavailable",error);}
    }
}
