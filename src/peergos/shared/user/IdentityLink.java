package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.regex.*;

public class IdentityLink implements Cborable {

    @JsType
    public enum KnownService {
        Peergos(0, Pattern.compile(Usernames.REGEX)),
        Twitter(1, Pattern.compile("^[A-Za-z0-9_]{1,15}$")),
        Facebook(2, Pattern.compile("^[a-z\\d.]{5,}$")),
        Website(3, Pattern.compile("^((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+[A-Za-z]{2,6}$")),
        Reddit(4, Pattern.compile("^[A-Za-z0-9]{1,20}$")),
        Github(5, Pattern.compile("^[a-z\\d](?:[a-z\\d]|-(?=[a-z\\d])){0,38}$")),
        HackerNews(6, Pattern.compile("^[a-z0-9_-]{2,15}$")),
        Lobsters(7, Pattern.compile("^[a-z0-9]{1,18}$")),
        LinkedIn(8, Pattern.compile("^[A-Za-z0-9-]{5,30}$")),
        Mastodon(9, Pattern.compile("@((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+[A-Za-z]{2,6}"));

        public int code;
        public Pattern usernameRegex;

        KnownService(int code, Pattern usernameRegex) {
            this.code = code;
            this.usernameRegex = usernameRegex;
        }

        private static Map<Integer, KnownService> lookup = new TreeMap<>();
        static {
            for (KnownService t: KnownService.values())
                lookup.put(t.code, t);
        }

        public static KnownService lookup(int t) {
            if (!lookup.containsKey(t))
                throw new IllegalStateException("Unknown Identity Service code: " + t);
            return lookup.get(t);
        }
    }

    public static class IdentityService implements Cborable {
        public final Either<KnownService, String> name;

        public IdentityService(Either<KnownService, String> name) {
            this.name = name;
        }

        public boolean isValidUsername(String username) {
            if (name.isB())
                return true;
            KnownService service = name.a();
            return service.usernameRegex.matcher(username).matches();
        }

        @JsMethod
        public String name() {
            return name.map(s -> s.name(), s -> s);
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            if (name.isA()) {
                state.put("t", new CborObject.CborLong(0));
                state.put("c", new CborObject.CborLong(name.a().code));
            } else {
                state.put("t", new CborObject.CborLong(1));
                state.put("n", new CborObject.CborString(name.b()));
            }
            return CborObject.CborMap.build(state);
        }

        public static IdentityService fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            long type = m.getLong("t");
            if (type == 0) {
                return new IdentityService(Either.a(KnownService.lookup((int)m.getLong("c"))));
            } else if (type == 1) {
                return new IdentityService(Either.b(m.getString("n")));
            } else throw new IllegalStateException("Unknown IdentityService type: " + type);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IdentityService that = (IdentityService) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        public static IdentityService parse(String name) {
            return new IdentityService(Either.a(KnownService.valueOf(name)));
        }
    }

    @JsProperty
    public final String usernameA, usernameB;
    @JsProperty
    public final IdentityService serviceA, serviceB;

    public IdentityLink(String usernameA, IdentityService serviceA, String usernameB, IdentityService serviceB) {
        if (! serviceA.isValidUsername(usernameA))
            throw new IllegalStateException("Invalid username for " + serviceA);
        if (! serviceB.isValidUsername(usernameB))
            throw new IllegalStateException("Invalid username for " + serviceA);
        this.usernameA = usernameA;
        this.serviceA = serviceA;
        this.usernameB = usernameB;
        this.serviceB = serviceB;
    }

    public String textToPost() {
        return "I am " + usernameA + " on " + serviceA.name() + " and " + usernameB + " on " + serviceB.name();
    }

    public static IdentityLink parse(String firstLine) {
        String[] parts = firstLine.trim().split(" ");
        if (!parts[0].equals("I") || !parts[1].equals("am") || !parts[3].equals("on") || !parts[5].equals("and") || !parts[7].equals("on"))
            throw new IllegalStateException("Invalid text for IdentityLink");
        String usernameA = parts[2];
        IdentityService serviceA = IdentityService.parse(parts[4]);
        String usernameB = parts[6];
        IdentityService serviceB = IdentityService.parse(parts[8]);
        return new IdentityLink(usernameA, serviceA, usernameB, serviceB);
    }

    public static IdentityLink decrypt(String encryptedPost, SymmetricKey key, PublicSigningKey identity) {
        CipherText parsed = CipherText.fromCbor(CborObject.fromByteArray(Base58.decode(encryptedPost.trim())));
        byte[] decrypted = parsed.decrypt(key, c -> ((CborObject.CborByteArray) c).value);
        byte[] unsigned = identity.unsignMessage(decrypted);
        return IdentityLink.fromCbor(CborObject.fromByteArray(unsigned));
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cborData = new TreeMap<>();
        cborData.put("ua", new CborObject.CborString(usernameA));
        cborData.put("sa", serviceA);
        cborData.put("ub", new CborObject.CborString(usernameB));
        cborData.put("sb", serviceB);
        return CborObject.CborMap.build(cborData);
    }

    public static IdentityLink fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for AlternativeIdentityClaim: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String usernameA = m.getString("ua");
        IdentityService serviceA = m.get("sa", IdentityService::fromCbor);
        String usernameB = m.getString("ub");
        IdentityService serviceB = m.get("sb", IdentityService::fromCbor);

        return new IdentityLink(usernameA, serviceA, usernameB, serviceB);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdentityLink that = (IdentityLink) o;
        return Objects.equals(usernameA, that.usernameA) &&
                Objects.equals(usernameB, that.usernameB) &&
                Objects.equals(serviceA, that.serviceA) &&
                Objects.equals(serviceB, that.serviceB);
    }

    @Override
    public int hashCode() {
        return Objects.hash(usernameA, usernameB, serviceA, serviceB);
    }
}
