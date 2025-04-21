package messenger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.swing.*;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Client
{
    private static final Map<String, JTextPane> chatAreas = new ConcurrentHashMap<>();
    private static final JPanel userPanel = new JPanel(new GridLayout(0, 1));
    private static final JFrame chatFrame = new JFrame();
    private static final CardLayout chatLayout = new CardLayout();
    private static final JPanel chatPanel = new JPanel(chatLayout);

    private static MessengerServiceGrpc.MessengerServiceStub asyncStub;
    private static MessengerServiceGrpc.MessengerServiceBlockingStub blockingStub;
    private static ManagedChannel channel;
    private static String username;
    private static boolean connected = true;

    public static void main(String[] args)
    {
        username = JOptionPane.showInputDialog("Enter your nickname:");
        if (username == null || username.trim().isEmpty()) return;

        chatFrame.setTitle("Messenger - " + username);
        setupChannelAndStub();
        setupUI();
        connectToServer();
    }

    private static void setupChannelAndStub()
    {
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        asyncStub = MessengerServiceGrpc.newStub(channel);
        blockingStub = MessengerServiceGrpc.newBlockingStub(channel);
    }

    private static void connectToServer()
    {
        Messenger.ConnectRequest connectRequest = Messenger.ConnectRequest.newBuilder().setUsername(username).build();
        Messenger.ConnectResponse response = blockingStub.connect(connectRequest);

        for (String user : response.getUsersList())
        {
            if (!user.equals(username))
            {
                addUserButton(user);
            }
        }

        startReceivingMessages();
    }

    private static void startReceivingMessages()
    {
        asyncStub.receiveMessages(Messenger.ReceiveRequest.newBuilder().setUsername(username).build(),
                new StreamObserver<>()
                {
                    @Override
                    public void onNext(Messenger.MessageResponse msg)
                    {
                        SwingUtilities.invokeLater(() ->
                        {
                            System.out.println("[DEBUG] Received message: " +
                                    "from=" + msg.getFrom() +
                                    ", content=\"" + msg.getContent() + "\"" +
                                    ", delete=" + msg.getDelete() +
                                    ", secret=" + msg.getSecret() +
                                    ", system=" + msg.getSystem());

                            if (msg.getDelete())
                            {
                                // 👇 FIX: Determine the right chat tab (whether you're sender or receiver)
                                String chatWith = msg.getFrom().equals(username) ? msg.getTo() : msg.getFrom();
                                JTextPane area = chatAreas.get(chatWith);
                                if (area != null)
                                {
                                    try
                                    {
                                        var doc = area.getStyledDocument();
                                        String target = "[" + (msg.getFrom().equals(username) ? "You" : msg.getFrom()) + "]: " + msg.getContent() + "\n";
                                        String fullText = doc.getText(0, doc.getLength());
                                        int start = fullText.indexOf(target);
                                        if (start != -1)
                                        {
                                            doc.remove(start, target.length());
                                        }
                                    }
                                    catch (Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                                return;
                            }

                            if (msg.getSystem())
                            {
                                if (!chatAreas.containsKey(msg.getFrom()) && !msg.getFrom().equals(username))
                                {
                                    addUserButton(msg.getFrom());
                                }
                            }
                            else
                            {
                                JTextPane pane = chatAreas.get(msg.getFrom());
                                if (pane != null)
                                {
                                    appendMessage(pane, "[" + msg.getFrom() + "]: " + msg.getContent(), msg.getSecret());
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        t.printStackTrace();
                    }

                    @Override
                    public void onCompleted()
                    {
                    }
                });
    }

    private static void setupUI()
    {
        chatFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        chatFrame.setSize(600, 400);

        userPanel.setPreferredSize(new Dimension(150, 0));
        JScrollPane userScroll = new JScrollPane(userPanel);

        JButton toggleButton = new JButton("Disconnect");
        toggleButton.addActionListener(e ->
        {
            if (connected)
            {
                blockingStub.disconnect(Messenger.DisconnectRequest.newBuilder().setUsername(username).build());
                channel.shutdownNow();
                toggleButton.setText("Reconnect");
                connected = false;
            }
            else
            {
                setupChannelAndStub();
                connectToServer();
                toggleButton.setText("Disconnect");
                connected = true;
            }
        });

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(userScroll, BorderLayout.CENTER);
        leftPanel.add(toggleButton, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, chatPanel);
        chatFrame.add(splitPane);
        chatFrame.setVisible(true);
    }

    private static void addUserButton(String target)
    {
        if (chatAreas.containsKey(target)) return;

        JButton userButton = new JButton(target);
        userPanel.add(userButton);

        JTextPane chatPane = new JTextPane();
        chatPane.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatPane);

        JTextField inputField = new JTextField();
        JCheckBox secretBox = new JCheckBox("Secret");

        inputField.addActionListener(e ->
        {
            if (!connected)
            {
                JOptionPane.showMessageDialog(chatFrame, "You are disconnected!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String content = inputField.getText();
            if (content.isBlank()) return;
            inputField.setText("");

            Messenger.MessageRequest msg = Messenger.MessageRequest.newBuilder()
                    .setFrom(username)
                    .setTo(target)
                    .setContent(content)
                    .setSecret(secretBox.isSelected())
                    .build();

            asyncStub.sendMessage(msg, new StreamObserver<>()
            {
                @Override
                public void onNext(Messenger.SendResponse sendResponse)
                {
                }

                @Override
                public void onError(Throwable throwable)
                {
                    throwable.printStackTrace();
                }

                @Override
                public void onCompleted()
                {
                }
            });

            appendMessage(chatPane, "[You]: " + content, secretBox.isSelected());
        });

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(secretBox, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        chatAreas.put(target, chatPane);
        chatPanel.add(panel, target);

        userButton.addActionListener(e -> chatLayout.show(chatPanel, target));
        chatFrame.revalidate();
    }

    private static void appendMessage(JTextPane pane, String message, boolean isSecret)
    {
        try
        {
            StyledDocument doc = pane.getStyledDocument();
            Style style = pane.addStyle("Style", null);
            StyleConstants.setForeground(style, isSecret ? Color.RED : Color.BLACK);
            doc.insertString(doc.getLength(), message + "\n", style);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void removeMessage(JTextPane pane, String message)
    {
        try
        {
            StyledDocument doc = pane.getStyledDocument();
            String fullText = doc.getText(0, doc.getLength());
            int index = fullText.indexOf(message);
            if (index >= 0)
            {
                doc.remove(index, message.length());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
