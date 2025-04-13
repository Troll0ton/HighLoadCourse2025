package messenger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.Scanner;

public class Client
{
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();

        MessengerServiceGrpc.MessengerServiceStub asyncStub = MessengerServiceGrpc.newStub(channel);
        MessengerServiceGrpc.MessengerServiceBlockingStub blockingStub = MessengerServiceGrpc.newBlockingStub(channel);

        System.out.print("Your nickname: ");
        String nickname = scanner.nextLine();

        asyncStub.receiveMessages(Messenger.ReceiveRequest.newBuilder().setUsername(nickname).build(),
                new StreamObserver<Messenger.MessageResponse>() {
                    @Override
                    public void onNext(Messenger.MessageResponse msg) {
                        System.out.println("\n[" + msg.getFrom() + "]: " + msg.getContent());
                        System.out.print("To: ");
                    }

                    @Override public void onError(Throwable t) { t.printStackTrace(); }
                    @Override public void onCompleted() {}
                });

        while (true) {
            System.out.print("To: ");
            String to = scanner.nextLine();
            System.out.print("Message: ");
            String content = scanner.nextLine();

            Messenger.MessageRequest request = Messenger.MessageRequest.newBuilder()
                    .setFrom(nickname)
                    .setTo(to)
                    .setContent(content)
                    .build();

            blockingStub.sendMessage(request);
        }
    }
}