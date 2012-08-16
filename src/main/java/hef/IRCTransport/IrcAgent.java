package hef.IRCTransport;

import java.io.IOException;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.SocketFactory;
import org.bukkit.entity.Player;
import org.pircbotx.Channel;
import org.pircbotx.PircBotX;
import static org.pircbotx.ReplyConstants.RPL_ENDOFWHOIS;
import static org.pircbotx.ReplyConstants.RPL_WHOISUSER;
import org.pircbotx.User;
import org.pircbotx.UtilSSLSocketFactory;
import org.pircbotx.exception.IrcException;

/**
 * Represent a player to an IRC server. Every Bukkit player should have one!
 * Every agent will have channels, a player, and an active channel.
 */
public class IrcAgent extends PircBotX {

    /**
     * Used to send message to the console.
     */
    private static final Logger LOG = Logger.getLogger("Minecraft");
    /**
     * The active channel.
     */
    private Channel activeChannel;
    /**
     * A reference to the Bukkit Player object.
     */
    private Player player;
    /**
     * A reference to the IRCTransport plugin instance.
     */
    private final IRCTransport plugin;
    /**
     * The settings object associated with this agent.
     */
    private AgentSettings settings;
    /**
     * Flag to indicate we should not reconnect.
     */
    private boolean shuttingDown;
    /**
     * Flag to indicate we are getting WHOIS.
     */
    private boolean recvWho = false;
    /**
     * A set of channels to suppress onUserList. This is used to hide initial
     * join messages.
     */
    private HashSet<Channel> suppressNames = new HashSet<Channel>();
    /**
     * A set of channels to suppress Topic message. This is used to hid initial
     * join messages.
     */
    private HashSet<Channel> suppressTopic = new HashSet<Channel>();

    /**
     * Agent Constructor.
     *
     * @param instance Reference to plugin instance.
     * @param bukkitPlayer Reference to Bukkit Player
     */
    public IrcAgent(final IRCTransport instance, final Player bukkitPlayer) {
        this.plugin = instance;
        this.player = bukkitPlayer;
        this.shuttingDown = false;
        setLogin(String.format("%s", player.getEntityId()));
        super.setAutoNickChange(true);

        // init player settings
        setSettings(plugin.getDatabase().find(AgentSettings.class, player.getName()));
        if (null == getSettings()) {
            setSettings(new AgentSettings(player));
            String prefix = plugin.getConfig().getString("default.prefix", "");
            String suffix = plugin.getConfig().getString("default.suffix", "");
            int ircnicksize = plugin.getConfig().getInt("server.nicksize", 15);
            String nick = String.format("%s%s%s", prefix, player.getName(), suffix);
            if (nick.length() > ircnicksize) {
                nick = nick.substring(0, ircnicksize);
            }
            getSettings().setIrcNick(nick);

        } else {
            String format = "Player '%s' using persistent IRC nick '%s'";
            String name = player.getName();
            String nick = getSettings().getIrcNick();
            LOG.log(Level.INFO, String.format(format, name, nick));
        }
        setNick(getSettings().getIrcNick());
        //this.getListenerManager().addListener(new IrcListener(instance));
    }

    /**
     * Connect the agent. Don't call this directly, call `new
     * Connect(this).run()` instead.
     *
     * @throws IOException If it was not possible to connect to the server.
     * @throws IrcException If the server would not let us join it.
     */
    public void connect() throws IOException, IrcException {
        String address = getPlugin().getConfig().getString("server.address");
        int port = getPlugin().getConfig().getInt("server.port");
        String password = getPlugin().getConfig().getString("server.password");

        SocketFactory socketFactory = null;

        //setup WEBIRC
        setWebIrcAddress(this.getPlayer().getAddress().getAddress());
        setWebIrcHostname(player.getAddress().getHostName());
        String webIrcPassword = getPlugin().getConfig().getString("server.webirc_password");
        if (webIrcPassword != null) {
            this.setWebIrcPassword(webIrcPassword);
        }

        if (getPlugin().getConfig().getBoolean("server.ssl.enabled", false)) {
            if (getPlugin().getConfig().getBoolean("server.ssl.trust", false)) {
                socketFactory = new UtilSSLSocketFactory().trustAllCertificates();
            } else {
                socketFactory = new UtilSSLSocketFactory();
            }
        }

        if (!isConnected()) {
            if (getServer() == null) {
                connect(address, port, password, socketFactory);
            } else {
                reconnect();
            }
        }
    }

    /**
     * Fetch the active channel. The active channel is the channel that a player
     * will talk in if they don't specify a channel.
     *
     * @return a string with the active channel name.
     */
    public Channel getActiveChannel() {
        return this.activeChannel;
    }

    /**
     * Get the Player.
     *
     * @return Reference to Bukkit Player
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * The IRCTransport plugin instance.
     *
     * @return a reference to the IRC plugin.
     */
    public IRCTransport getPlugin() {
        return plugin;
    }

    /**
     * @return the settings
     */
    public AgentSettings getSettings() {
        return settings;
    }

    /**
     * Shutting Down Flag Useful for preventing reconnection measures.
     *
     * @return Is the agent shutting down?
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Log stuff. This method only logs to INFO if the Verbose flags is set.
     *
     * @param line The line you want logged to console.
     */
    @Override
    public void log(final String line) {
        if (plugin.getConfig().getBoolean("verbose")) {
            LOG.log(Level.INFO, line);
        }
    }

    /**
     * call names(activechannel).
     */
    protected void names() {
        names(activeChannel);
    }

    /**
     * Get a list of playernames from a channel. removes muteNames flag for
     * channel.
     *
     * @param activeChannel2 The channel to list names from.
     */
    protected void names(final Channel activeChannel2) {
        StringBuilder usersString = new StringBuilder();
        for (User user : getUsers(activeChannel2)) {
            usersString.append(user.getNick());
            usersString.append(" ");
        }
        String format = plugin.getConfig().getString("messages.list");
        String channel = activeChannel2.getName();
        String message = format.replace("${LIST}", usersString.toString()).replace("${CHANNEL}", channel);
        getPlayer().sendMessage(message.replace("&", "\u00A7"));
    }

    /**
     * Save agent settings to persistent data store.
     */
    protected void saveSettings() {
        plugin.getDatabase().save(getSettings());
    }

    /**
     * Action sender. triggers when player sends a /me
     *
     * @param action The content of the action.
     */
    public void sendAction(final String action) {
        String actiontr = action;
        String trans = plugin.getConfig().getString("translations." + action, "");
        String format = plugin.getConfig().getString("messages.action");

        if (!trans.equals("")) {
            actiontr = trans;
        }

        sendAction(activeChannel, actiontr);

        String message = format.replace("${CHANNEL}", activeChannel.getName());
        message = message.replace("${NICK}", getPlayer().getDisplayName());
        message = message.replace("${ACTION}", actiontr);

        getPlayer().sendMessage(message.replace("&", "\u00A7"));
    }

    /**
     * Sends a message to the active channel.
     *
     * @param message The message to send
     */
    public void sendMessage(final String message) {
        sendMessage(activeChannel, message);
    }

    /**
     * Change active channel.
     *
     * @param channel The channel to make the active one.
     */
    public void setActiveChannel(final Channel channel) {
        this.activeChannel = channel;
    }

    /**
     * Set name to attempt to use at login This function is not the same as
     * changeNick(String name) you probably don't want this function.
     *
     * @param name the name to attempt to use.
     */
    @Override
    public void setNick(final String name) {
        super.setName(name);
    }

    /**
     * Set the settings object.
     *
     * @param agentSettings the settings to set
     */
    public void setSettings(final AgentSettings agentSettings) {
        this.settings = agentSettings;
    }

    /**
     * Attempt to set the channel topic. Sends to active channel.
     *
     * @param topic The body of the topic to set.
     */
    protected void setTopic(final String topic) {
        setTopic(activeChannel, topic);
    }

    /**
     * Initiate agent shutdown Disconnects the agent, sets shutting down flag.
     */
    public void shutdown() {
        if (isConnected() && !shuttingDown) {
            shuttingDown = true;
            disconnect();
        }
    }

    /**
     * Request active topic.
     */
    protected void topic() {
        getSuppressTopic().remove(activeChannel);
        sendRawLine(String.format("TOPIC %s", activeChannel.getName()));
    }

    /**
     * Request information about a nick.
     *
     * @param nick a command delimited list of nicks.
     */
    protected void whois(final String nick) {
        sendRawLine(String.format("WHOIS %s", nick));
    }

    /**
     * @return The hash set of channels to suppress user list in.
     */
    public HashSet<Channel> getSuppressNames() {
        return suppressNames;
    }

    /**
     * @return the Hash set of channels to suppress topic messages in.
     */
    public HashSet<Channel> getSuppressTopic() {
        return suppressTopic;
    }

    /**
     * Parse /WHOIS data since PircBotX does not.
     *
     * @param code the integer code for the message
     * @param response the response from the server
     */
    @Override
    protected void processServerResponse(final int code, String response) {
        // Process WHOIS data
        if (code == RPL_WHOISUSER) {
            recvWho = true;
        } else if (code == RPL_ENDOFWHOIS) {
            recvWho = false;
        }
        if (recvWho) {
            response = response.replaceFirst(getNick() + " ", "&4");
            getPlayer().sendMessage(response.replace("&", "\u00A7"));
        }

        super.processServerResponse(code, response);
    }
}
