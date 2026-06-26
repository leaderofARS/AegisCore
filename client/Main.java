import javax.swing.*;

/**
 * Entry point for the AegisCore 2D Action RPG multiplayer client.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Prompt the player for their display name and the server address.</li>
 *   <li>Create and configure the main {@link JFrame} window (non-resizable,
 *       centred on screen, EXIT_ON_CLOSE).</li>
 *   <li>Instantiate {@link GamePanel}, add it to the frame, pack the window,
 *       make it visible, and then hand off control to the game thread.</li>
 * </ol>
 *
 * <p>The window is made visible <em>before</em> {@code startGameThread()} is
 * called so that the {@link GamePanel} has a valid {@link java.awt.Graphics}
 * context when the first frame is rendered.
 */
public class Main {

    public static void main(String[] args) {

        // ── Collect player identity ───────────────────────────────────────────
        String playerName = JOptionPane.showInputDialog(
                null,
                "Enter your player name:",
                "AegisCore RPG — Join",
                JOptionPane.PLAIN_MESSAGE);

        if (playerName == null || playerName.isBlank()) {
            System.out.println("[Main] No name entered — exiting.");
            System.exit(0);
        }
        playerName = playerName.trim();

        String serverInput = (String) JOptionPane.showInputDialog(
                null,
                "Server address (leave blank for localhost):",
                "AegisCore RPG — Connect",
                JOptionPane.PLAIN_MESSAGE,
                null, null,
                "localhost");

        String serverAddress = (serverInput == null || serverInput.isBlank())
                ? "localhost"
                : serverInput.trim();

        // ── Build the window on the Event Dispatch Thread ─────────────────────
        final String finalName    = playerName;
        final String finalAddress = serverAddress;

        SwingUtilities.invokeLater(() -> {

            // Create the game panel first (so preferred size is known before pack())
            GamePanel gamePanel = new GamePanel(finalName, finalAddress);

            // Configure the JFrame
            JFrame window = new JFrame("AegisCore RPG  —  " + finalName);
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setResizable(false);   // fixed 768×576 viewport
            window.add(gamePanel);
            window.pack();                // size frame to panel's preferred size
            window.setLocationRelativeTo(null); // centre on screen

            // Register a shutdown hook to cleanly disconnect on window close
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    gamePanel.shutdown();
                }
            });

            window.setVisible(true);

            // Start the game loop AFTER the window is visible (Graphics context ready)
            gamePanel.startGameThread();
        });
    }
}
