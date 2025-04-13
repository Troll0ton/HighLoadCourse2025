package messenger;

import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessengerImpl extends MessengerServiceGrpc.MessengerServiceImplBase
{
    private final Map<String, StreamObserver<Messenger.MessageResponse>> listeners = new ConcurrentHashMap<>();

    @Override
    public void sendMessage(Messenger.MessageRequest request, StreamObserver<Messenger.SendResponse> responseObserver)
    {
        Messenger.MessageResponse msg = Messenger.MessageResponse.newBuilder()
                .setFrom(request.getFrom())
                .setContent(request.getContent())
                .build();

        StreamObserver<Messenger.MessageResponse> receiver = listeners.get(request.getTo());
        if (receiver != null)
        {
            receiver.onNext(msg);
        }

        Messenger.SendResponse res = Messenger.SendResponse.newBuilder().setStatus("Delivered").build();
        responseObserver.onNext(res);
        responseObserver.onCompleted();
    }

    @Override
    public void receiveMessages(Messenger.ReceiveRequest request, StreamObserver<Messenger.MessageResponse> responseObserver)
    {
        listeners.put(request.getUsername(), responseObserver);
    }
}
