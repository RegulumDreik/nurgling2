package nurgling.actions;

import haven.*;
import haven.res.ui.tt.cn.CustomName;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.*;
import nurgling.tools.NAlias;

import java.util.ArrayList;

public class TransferToBarrel implements Action{

    Gob barrel;
    NAlias items;

    // Capacity of a barrel, in the units the barrel tooltip counts: pieces for countable
    // contents, litres for liquids. What matters is the room left (capacity minus current
    // content), not a flat threshold - a barrel holding 9257 of 10000 still takes a stack.
    static final int COUNT_CAP = 10000;
    static final int LIQUID_CAP = 100;

    int th = COUNT_CAP;

    // Set when part of the carried load did not fit, i.e. the barrel is out of room and the
    // caller should move on to the next one.
    boolean full = false;

    // When set, use exact name matching instead of NAlias substring matching
    String exactName = null;

    public TransferToBarrel(Gob barrel, NAlias items) {
        this.barrel = barrel;
        this.items = items;
    }

    public TransferToBarrel(Gob barrel, NAlias items, int th) {
        this(barrel, items);
        this.th = th;
    }

    public TransferToBarrel(Gob barrel, String exactName) {
        this.barrel = barrel;
        this.exactName = exactName;
        this.items = new NAlias(exactName);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        if(barrel==null){
            return Results.ERROR("NULL BARREL");
        }
        new PathFinder( barrel ).run (gui);
        if ( !(new OpenTargetContainer (  "Barrel",barrel ).run ( gui ).isSuccess) ) {
            return Results.ERROR("OPEN FAIL");
        }
        // The barrel's content is read off its tooltip, and that tooltip arrives a few ticks
        // after the window itself - OpenTargetContainer only waits for the RelCont. Reading it
        // straight away yields -1 ("unknown"), which used to be taken as "no room" and skipped
        // the whole barrel silently.
        NUtils.addTask(new NTask() {
            { infinite = false; }   // give up after the usual tick limit instead of blocking

            @Override
            public boolean check() {
                return gui.getBarrelContent() >= 0;
            }
        });
        double barrelCont = gui.getBarrelContent();
        if(barrelCont>-1) {

            final ArrayList<WItem> witems = getMatchingItems(gui);
            // How much an item holds comes from its tooltip, which loads asynchronously; without
            // it nothing can be picked, so wait for the sizes before deciding anything.
            NUtils.addTask(new NTask() {
                { infinite = false; }

                @Override
                public boolean check() {
                    for (WItem item : witems) {
                        if (size(item) < 0)
                            return false;
                    }
                    return true;
                }
            });

            ArrayList<WItem> targetItems = new ArrayList<>();
            double sum = 0;
            boolean sized = true;
            for (WItem item : witems) {
                double size = size(item);
                if (size < 0) {
                    sized = false;
                    continue;
                }
                double cap = isLiquid(item) ? LIQUID_CAP : th;
                if (barrelCont + sum + size <= cap) {
                    sum += size;
                    targetItems.add(item);
                }
            }
            // Full means "the barrel had no room for what we carry" - only then does the caller
            // move on to the next barrel. A pick that failed for any other reason (sizes never
            // arrived) is not fullness, and saying so sent the bot marching past empty barrels.
            full = sized && targetItems.size() < witems.size();

            if(!targetItems.isEmpty()) {
                NUtils.takeItemToHand(targetItems.get(0));
                if(witems.size() == targetItems.size()) {
                    if(barrelCont == 0)
                    {
                        NUtils.activateItem(barrel, true);
                        if (targetItems.size()>1) {
                            NUtils.getUI().core.addTask(new NotThisInHand(NUtils.getGameUI().vhand));
                        }
                    }
                    NUtils.dropsame(barrel);
                    NUtils.getUI().core.addTask(new WaitItems(NUtils.getGameUI().getInventory(), items, 0));
                }
                else
                {
                    for (int i = 0; i < targetItems.size(); i++) {
                        NUtils.activateItem(barrel, true);
                        if (i + 1 < targetItems.size()) {
                            NUtils.getUI().core.addTask(new NotThisInHand(NUtils.getGameUI().vhand));
                        }
                    }
                    NUtils.getUI().core.addTask(new WaitItems(NUtils.getGameUI().getInventory(), items, witems.size() - targetItems.size() - 1));


                    if (NUtils.getGameUI().vhand != null ) {
                        NUtils.getUI().core.addTask(new WaitItemInHand());
                        gui.getInventory().dropOn(gui.getInventory().findFreeCoord(NUtils.getGameUI().vhand));
                    }
                }
            }
        }
        new CloseTargetContainer ( "Barrel" ).run ( gui );
        return Results.SUCCESS();
    }

    public boolean isFull()
    {
        return full;
    }

    /**
     * How much of a barrel this item takes up, in the units the barrel counts: pieces for
     * countable items, litres for liquids. -1 while the item's tooltip has not loaded yet.
     */
    private static double size(WItem item) {
        GItem.Amount amount = ((NGItem) item.item).getInfo(GItem.Amount.class);
        if (amount != null)
            return amount.itemnum();
        CustomName cn = ((NGItem) item.item).getInfo(CustomName.class);
        if (cn != null && cn.count > 0)
            return cn.count;
        return -1;
    }

    private static boolean isLiquid(WItem item) {
        return ((NGItem) item.item).getInfo(GItem.Amount.class) == null;
    }

    /**
     * Gets items from inventory, using exact name match if exactName is set,
     * otherwise uses NAlias substring matching.
     */
    private ArrayList<WItem> getMatchingItems(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> allItems = gui.getInventory().getItems(items);
        if (exactName == null) {
            return allItems;
        }
        ArrayList<WItem> exactMatches = new ArrayList<>();
        for (WItem witem : allItems) {
            if (((NGItem) witem.item).name().equals(exactName)) {
                exactMatches.add(witem);
            }
        }
        return exactMatches;
    }
}
