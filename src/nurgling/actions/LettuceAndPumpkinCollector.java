package nurgling.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.NWItem;
import nurgling.areas.NArea;
import nurgling.tasks.*;
import nurgling.tools.Container;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Arrays;

public class LettuceAndPumpkinCollector implements Action {
    NArea input;
    NArea seedOutput;
    NArea itemOutput;
    NArea troughArea;
    NArea swillArea;
    NAlias items;
    String secondaryItemAlias;
    boolean isQualityGrid = false;

    public LettuceAndPumpkinCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, NArea troughArea) {
        this.input = input;
        this.seedOutput = seedOutput;
        this.itemOutput = itemOutput;
        this.items = items;
        this.troughArea = troughArea;
        this.secondaryItemAlias = items.keys.contains("Head of Lettuce") ? "Lettuce Leaf" : "Pumpkin Flesh";
    }

    public LettuceAndPumpkinCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, NArea troughArea, NArea swillArea) {
        this(input, seedOutput, itemOutput, items, troughArea);
        this.swillArea = swillArea;
    }

    public LettuceAndPumpkinCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, NArea troughArea, boolean isQualityGrid) {
        this(input, seedOutput, itemOutput, items, troughArea);
        this.isQualityGrid = isQualityGrid;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        // Preserve any exceptions the caller passed (e.g. "flesh" to keep the by-product
        // out of the search) and add the standard container exclusions.
        ArrayList<String> exceptions = new ArrayList<>(items.exceptions);
        exceptions.add("stockpile");
        exceptions.add("barrel");
        // A growing crop and the item it yields share a name - "gfx/terobjs/plants/pumpkin"
        // vs "gfx/terobjs/items/pumpkin" - so an item alias like "Pumpkin" (substring match)
        // also hits the planted crop. Picking up from the earth can never apply to a plant,
        // so exclude the plant path outright: on a partially planted field takeFromEarth
        // would otherwise wait forever for a gob that never goes away.
        exceptions.add("plants/");
        NAlias collected_items = new NAlias(items.keys, exceptions);
        ArrayList<WItem> testItems;

        int totalItemsThatCanFit = 0;
        int currentQuantity = 0;

        while (!Finder.findGobs(input, collected_items).isEmpty()) {
            if (!(testItems = gui.getInventory().getItems(items)).isEmpty()) {
                totalItemsThatCanFit = Math.max(gui.getInventory().getNumberFreeCoord(testItems.get(0)) + 1, totalItemsThatCanFit);
                currentQuantity = gui.getInventory().getItems(items).size();

                if ((this.items.keys.contains("Head of Lettuce") && gui.getInventory().getNumberFreeCoord(testItems.get(0)) <= Math.floor(totalItemsThatCanFit/2))
                        || (this.items.keys.contains("Pumpkin") && gui.getInventory().getNumberFreeCoord(testItems.get(0)) == 0)) {
                    splitItems(gui);

                    if (!(testItems = gui.getInventory().getItems(new NAlias("Seed"))).isEmpty()) {
                        transferSeeds(gui);
                    }

                    if (!(testItems = gui.getInventory().getItems(new NAlias(this.secondaryItemAlias))).isEmpty()) {
                        new TransferToPiles(itemOutput.getRCArea(), new NAlias(this.secondaryItemAlias)).run(gui);
                    }

                    currentQuantity = 0;
                }
            }

            Gob item = Finder.findGob(collected_items);
            if (item == null)
                break;
            if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x) {
                PathFinder pf = new PathFinder(item);
                pf.run(gui);
            }
            NUtils.takeFromEarth(item);
            NUtils.getUI().core.addTask(new WaitMoreItems(NUtils.getGameUI().getInventory(), items, currentQuantity+1));
        }

        splitItems(gui);

        if (!(testItems = gui.getInventory().getItems(new NAlias("Seed"))).isEmpty()) {
            transferSeeds(gui);
        }

        if (!(testItems = gui.getInventory().getItems(new NAlias(this.secondaryItemAlias))).isEmpty()) {
            new TransferToPiles(itemOutput.getRCArea(), new NAlias(this.secondaryItemAlias)).run(gui);
        }

        return Results.SUCCESS();
    }

    private void transferSeeds(NGameUI gui) throws InterruptedException {
        if (isQualityGrid) {
            // Quality mode: transfer seeds to containers
            ArrayList<Container> containers = new ArrayList<>();
            for (Gob sm : Finder.findGobs(seedOutput.getRCArea(), new NAlias(new ArrayList<>(NContext.contcaps.keySet())))) {
                Container cand = new Container(sm, NContext.contcaps.get(sm.ngob.name), null);
                cand.initattr(Container.Space.class);
                containers.add(cand);
            }

            if (containers.isEmpty())
                throw new RuntimeException("No container found in seed area!");

            Container container = containers.get(0);
            new TransferToContainer(container, new NAlias("Seed")).run(gui);
            new CloseTargetContainer(container).run(gui);
        } else {
            // Regular mode: transfer seeds to barrels, then trough
            ArrayList<Gob> barrels = Finder.findGobs(seedOutput, new NAlias("barrel"));

            boolean barrelsFull = !barrels.isEmpty();
            for (Gob barrel : barrels) {
                TransferToBarrel tb = new TransferToBarrel(barrel, new NAlias("Seed"));
                tb.run(gui);
                if (!tb.isFull()) {
                    barrelsFull = false;
                    break;
                }
            }

            boolean troughFound = false;
            boolean troughFull = false;
            if (troughArea != null && !gui.getInventory().getItems(new NAlias("Seed")).isEmpty()) {
                Gob trough = Finder.findGob(troughArea, new NAlias("gfx/terobjs/trough"));
                if (trough == null) {
                    // Gobs only exist within the map load radius, so a trough area at the far end
                    // of the field holds nothing until we walk over there. Head that way once and
                    // look again before declaring it troughless. Stop a few tiles short of the
                    // area rather than aiming at its centre - the trough usually stands in it, and
                    // walking onto the thing we are looking for just fails to path.
                    Pair<Coord2d, Coord2d> ta = troughArea.getRCArea();
                    Coord2d centre = ta.a.add(ta.b).div(2);
                    Coord2d from = gui.map.player().rc;
                    double dist = centre.dist(from);
                    double stop = MCache.tilesz2.x * 3;
                    new PathFinder(dist > stop ? from.add(centre.sub(from).mul((dist - stop) / dist)) : centre).run(gui);
                    trough = Finder.findGob(troughArea, new NAlias("gfx/terobjs/trough"));
                }
                if (trough != null) {
                    troughFound = true;
                    // Pass the cistern along, as HarvestCrop does: without it a full trough is a
                    // dead end and the seeds stay in the inventory, while with it the trough gets
                    // emptied into the cistern and keeps taking the rest of the harvest.
                    Gob cistern = swillArea != null ? Finder.findGob(swillArea, new NAlias("gfx/terobjs/cistern")) : null;
                    troughFull = trough.ngob.getModelAttribute() == 7 && cistern == null;
                    new TransferToTrough(trough, new NAlias("Seed"), cistern).run(gui);
                }
            }

            // No pile fallback: these seeds only go into barrels/troughs. Right-clicking the
            // ground with them is a no-op the server never answers, so PileMaker would wait
            // for a placement ghost that never appears and the bot would hang there forever.
            ArrayList<WItem> left = gui.getInventory().getItems(new NAlias("Seed"));
            if (!left.isEmpty()) {
                if (barrelsFull && !troughFound) {
                    gui.error("Seed storage is full - barrels are full and no trough was found");
                } else if (barrelsFull && troughFull) {
                    gui.error("Seed storage is full - barrels and trough are full");
                } else {
                    gui.error("Seed storage is full");
                }
            }
        }
    }

    private void splitItems(NGameUI gui) throws InterruptedException {
        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
        ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(this.items);
        for (WItem item : items) {
            if(this.items.keys.contains("Head of Lettuce")) {
                new SelectFlowerAction("Split", (NWItem) item).run(gui);
            } else if(this.items.keys.contains("Pumpkin")) {
                new SelectFlowerAction("Slice", (NWItem) item).run(gui);
            }

        }
    }
}
