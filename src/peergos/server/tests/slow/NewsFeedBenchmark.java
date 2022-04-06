package peergos.server.tests.slow;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.storage.DelayingStorage;
import peergos.server.tests.PeergosNetworkUtils;
import peergos.server.tests.UserTests;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.display.FileRef;
import peergos.shared.display.Text;
import peergos.shared.messaging.ChatController;
import peergos.shared.messaging.MessageEnvelope;
import peergos.shared.messaging.Messenger;
import peergos.shared.messaging.messages.ApplicationMessage;
import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.social.SharedItem;
import peergos.shared.social.SocialFeed;
import peergos.shared.social.SocialPost;
import peergos.shared.user.SocialState;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.cryptree.CryptreeNode;
import peergos.shared.util.Pair;
import peergos.shared.util.Serialize;

import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RunWith(Parameterized.class)
public class NewsFeedBenchmark {

    private static int RANDOM_SEED = 666;
    private final UserService service;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public NewsFeedBenchmark(String useIPFS, Random r) throws Exception {
        Pair<UserService, NetworkAccess> pair = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
        this.service = pair.left;
        this.network = pair.right;
    }

    private static Pair<UserService, NetworkAccess> buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        UserService service = Main.PKI_INIT.main(args);
        NetworkAccess net = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join();
        int delayMillis = 50;
        NetworkAccess delayed = DelayingStorage.buildNetwork(net, delayMillis, delayMillis);
        return new Pair<>(service, delayed);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
//                {"IPFS", new Random(0)}
                {"NOTIPFS", new Random(0)}
        });
    }

    public List<UserContext> getUserContexts(NetworkAccess network, int size, List<String> passwords) {
        return IntStream.range(0, size)
                .mapToObj(e -> {
                    String username = generateUsername();
                    String password = passwords.get(e);
                    try {
                        return ensureSignedUp(username, password, network.clear(), crypto);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }).collect(Collectors.toList());
    }

    public static void friendBetweenGroups(List<UserContext> a, List<UserContext> b) {
        for (UserContext userA : a) {
            for (UserContext userB : b) {
                // send initial request
                userA.sendFollowRequest(userB.username, SymmetricKey.random()).join();

                // make sharer reciprocate all the follow requests
                List<FollowRequestWithCipherText> sharerRequests = userB.processFollowRequests().join();
                for (FollowRequestWithCipherText u1Request : sharerRequests) {
                    AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
                    Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
                    boolean accept = true;
                    boolean reciprocate = true;
                    userB.sendReplyFollowRequest(u1Request, accept, reciprocate).join();
                }

                // complete the friendship connection
                userA.processFollowRequests().join();
            }
        }
    }

    //before
    // createNewPost(9) duration: 1422 mS, best: 1408 mS, worst: 3873 mS, av: 1681 mS
    // getSocialFeed & update (9) duration: 8127 mS, best: 7870 mS, worst: 27635 mS, av: 10028 mS
    // getSharedFiles (9) duration: 4236 mS, best: 528 mS, worst: 4236 mS, av: 2406 mS
    //after
    // createNewPost(9) duration: 1960 mS, best: 1884 mS, worst: 4774 mS, av: 2237 mS
    // getSocialFeed & update (9) duration: 11975 mS, best: 11789 mS, worst: 26516 mS, av: 13385 mS
    // getSharedFiles (9) duration: 4786 mS, best: 936 mS, worst: 4786 mS, av: 2860 mS
    @Test
    public void socialFeedTest() {
        String username = generateUsername();
        String password = "test01";
        UserContext a = ensureSignedUp(username, password, network, crypto);

        List<UserContext> shareeUsers = getUserContexts(network, 1, Arrays.asList(password));
        UserContext b = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        Messenger msgA = new Messenger(a);

        long worst1 = 0, best1 = Long.MAX_VALUE, accum1 = 0;
        long worst2 = 0, best2 = Long.MAX_VALUE, accum2 = 0;
        long worst3 = 0, best3 = Long.MAX_VALUE, accum3 = 0;
        int limit = 10;

        SocialFeed feed = a.getSocialFeed().join();
        for (int i = 0; i < limit; i++) {
            SocialPost post = new SocialPost(a.username,
                    Arrays.asList(new Text("post 1")), LocalDateTime.now(),
                    SocialPost.Resharing.Friends, Optional.empty(),
                    Collections.emptyList(), Collections.emptyList());

            long t1 = System.currentTimeMillis();
            Pair<Path, FileWrapper> p = feed.createNewPost(post).join();
            long duration1 = System.currentTimeMillis() - t1;
            accum1 = accum1 + duration1;
            worst1 = Math.max(worst1, duration1);
            best1 = Math.min(best1, duration1);
            System.err.printf("createNewPost(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration1, best1, worst1, (accum1) / (i + 1));

            String aFriendsUid = a.getGroupUid(SocialState.FRIENDS_GROUP_NAME).join().get();
            a.shareReadAccessWith(p.left, Set.of(aFriendsUid)).join();

            long t2 = System.currentTimeMillis();
            SocialFeed bFeed = b.getSocialFeed().join().update().join();
            long duration2 = System.currentTimeMillis() - t2;
            accum2 = accum2 + duration2;
            worst2 = Math.max(worst2, duration2);
            best2 = Math.min(best2, duration2);
            System.err.printf("getSocialFeed & update (%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration2, best2, worst2, (accum2) / (i + 1));

            long t3 = System.currentTimeMillis();
            List<Pair<SharedItem, FileWrapper>> bPosts = bFeed.getSharedFiles(0, 25).join();
            long duration3 = System.currentTimeMillis() - t3;
            accum3 = accum3 + duration3;
            worst3 = Math.max(worst3, duration3);
            best3 = Math.min(best3, duration3);
            System.err.printf("getSharedFiles (%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration3, best3, worst3, (accum3) / (i + 1));
        }
        System.currentTimeMillis();
    }


    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    public static <V> Pair<V, Long> time(Supplier<V> work) {
        long t0 = System.currentTimeMillis();
        V res = work.get();
        long t1 = System.currentTimeMillis();
        return new Pair<>(res, t1 - t0);
    }
}
