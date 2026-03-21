import java.awt.*;

/**
 * A {@link FlowLayout} subclass that wraps components to the next row when the
 * container is too narrow.  Standard FlowLayout clips; WrapLayout grows the
 * container's preferred height so all components remain visible.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return computeSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return computeSize(target, false);
    }

    private Dimension computeSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int maxWidth = target.getWidth();
            boolean unconstrained = (maxWidth == 0);
            if (unconstrained) maxWidth = Integer.MAX_VALUE;
            int availableWidth = maxWidth - insets.left - insets.right - getHgap() * 2;

            int x = 0;
            int y = insets.top + getVgap();
            int rowHeight = 0;
            int maxRowWidth = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component c = target.getComponent(i);
                if (!c.isVisible()) continue;
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (x > 0 && x + d.width > availableWidth) {
                    maxRowWidth = Math.max(maxRowWidth, x);
                    y += rowHeight + getVgap();
                    x = 0;
                    rowHeight = 0;
                }
                x += d.width + getHgap();
                rowHeight = Math.max(rowHeight, d.height);
            }
            maxRowWidth = Math.max(maxRowWidth, x);
            y += rowHeight + getVgap();
            int returnWidth = unconstrained
                    ? (maxRowWidth + insets.left + insets.right + getHgap() * 2)
                    : maxWidth;
            return new Dimension(returnWidth, y + insets.bottom);
        }
    }
}
