import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec;
import java.security.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class PqcCheck {
  public static void main(String[] a) throws Exception {
    Security.addProvider(new BouncyCastlePQCProvider());
    Provider pqc = Security.getProvider("BCPQC");

    // KeyPairGenerator with KyberParameterSpec
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("Kyber", pqc);
    kpg.initialize(KyberParameterSpec.kyber768, new SecureRandom());
    KeyPair bob = kpg.generateKeyPair();
    System.out.println("KeyPair OK pub=" + bob.getPublic().getEncoded().length + " priv=" + bob.getPrivate().getEncoded().length);

    // KEM via Cipher WRAP/UNWRAP with a real shared secret
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    SecretKey skey = new SecretKeySpec(secret, "AES");

    Cipher kem = Cipher.getInstance("Kyber", pqc);
    kem.init(Cipher.WRAP_MODE, bob.getPublic(), new SecureRandom());
    byte[] ct = kem.wrap(skey);
    System.out.println("Encaps OK ct=" + ct.length);

    Cipher kem2 = Cipher.getInstance("Kyber", pqc);
    kem2.init(Cipher.UNWRAP_MODE, bob.getPrivate(), new SecureRandom());
    SecretKey unwrapped = (SecretKey) kem2.unwrap(ct, "AES", Cipher.SECRET_KEY);
    boolean match = java.util.Arrays.equals(secret, unwrapped.getEncoded());
    System.out.println("Decaps MATCH=" + match);
  }
}
