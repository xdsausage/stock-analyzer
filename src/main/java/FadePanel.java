import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A {@link JPanel} subclass that can animate from fully transparent to fully
 * opaque.  Call {@link #fadeIn()} to start the animation whenever the panel
 * transitions from hidden to visible.
 *
 * <p>The fade is achieved by overriding {@link #paint(Graphics)} and applying
 * an {@link AlphaComposite} before delegating to the normal Swing paint cycle,
 * so all child components and the panel background are affected uniformly.
 */
public class FadePanel extends JPanel {

    /** Current opacity: 0 = fully transparent, 1 = fully opaque. */
    private float alpha = 1f;

    /** Timer that drives the fade-in animation at ~60 fps. */
    private Timer fadeTimer;

    // =========================================================================
    // Animation control
    // =========================================================================

    /**
     * Resets alpha to 0 and starts a 16 ms fade-in animation that increments
     * alpha by 0.05 per tick until it reaches 1.0 (~320 ms total).
     * Safe to call while a previous animation is still running.
     */
    public void fadeIn() {
        alpha = 0f;
        if (fadeTimer != null) fadeTimer.stop();
        fadeTimer = new Timer(16, null);
        fadeTimer.addActionListener(e -> {
            alpha = Math.min(1f, alpha + 0.05f);
            repaint();
            if (alpha >= 1f) {
                fadeTimer.stop();
            }
        });
        fadeTimer.start();
    }

    /**
     * Immediately sets alpha to 1 (fully opaque) and stops any running
     * animation.  Call this when the panel should appear instantly.
     */
    public void resetToVisible() {
        alpha = 1f;
        if (fadeTimer != null) {
            fadeTimer.stop();
        }
        repaint();
    }

    // =========================================================================
    // Painting
    // =========================================================================

    /**
     * Overrides the full paint pass (background + children) to apply the
     * current alpha composite, so the entire panel fades uniformly.
     */
    @Override
    public void paint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        super.paint(g2);
        g2.dispose();
    }
}
