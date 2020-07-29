package mindustry.ui.fragments;

import arc.*;
import arc.func.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.annotations.Annotations.*;
import mindustry.core.GameState.*;
import mindustry.entities.*;
import mindustry.entities.type.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.net.Administration.*;
import mindustry.net.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;

import static mindustry.Vars.*;

public class BlockInventoryFragment extends Fragment{
    private final static float holdWithdraw = 20f;

    private Table table = new Table();
    private Tile tile;
    private float holdTime = 0f;
    private boolean holding;
    private Item lastItem;

    @Remote(called = Loc.server, targets = Loc.both, forward = true)
    public static void requestItem(Player player, Tile tile, Item item, int amount){
        if(player == null || tile == null || !tile.interactable(player.getTeam())) return;
        amount = Mathf.clamp(amount, 0, player.getItemCapacity());
        int fa = amount;

        if(net.server() && (!Units.canInteract(player, tile) ||
            !netServer.admins.allowAction(player, ActionType.withdrawItem, tile, action -> {
                action.item = item;
                action.itemAmount = fa;
            }))) throw new ValidateException(player, "Player cannot request items.");

        int removed = tile.block().removeStack(tile, item, amount);

        player.addItem(item, removed);
        Events.fire(new WithdrawEvent(tile, player, item, amount));
        for(int j = 0; j < Mathf.clamp(removed / 3, 1, 8); j++){
            Time.run(j * 3f, () -> Call.transferItemEffect(item, tile.drawx(), tile.drawy(), player));
        }
    }

    @Override
    public void build(Group parent){
        table.setName("inventory");
        table.setTransform(true);
        parent.setTransform(true);
        parent.addChild(table);
    }

    public void showFor(Tile t){
        if(this.tile == t){
            hide();
            return;
        }
        this.tile = t;
        if(tile == null || tile.entity == null || !tile.block().isAccessible() || tile.entity.items.total() == 0)
            return;
        rebuild(true);
    }

    public void hide(){
        if(table == null) return;

        table.actions(Actions.scaleTo(0f, 1f, 0.06f, Interpolation.pow3Out), Actions.run(() -> {
            table.clearChildren();
            table.clearListeners();
            table.update(null);
        }), Actions.visible(false));
        table.touchable(Touchable.disabled);
        tile = null;
    }

    private void rebuild(boolean actions){

        // cutom
        for(int i = 0; i < state.teams.get(player.getTeam()).cores.size; i++) {
            if(state.teams.get(player.getTeam()).cores.get(i).tile.entity.id == tile.entity.id) {
                rebuildCore(actions);
                break;
            }
        }

        IntSet container = new IntSet();

        table.clearChildren();
        table.clearActions();
        table.background(Tex.inventory);
        table.touchable(Touchable.enabled);
        table.update(() -> {

            if(state.is(State.menu) || tile == null || tile.entity == null || !tile.block().isAccessible() || tile.entity.items.total() == 0){
                hide();
            }else{
                if(holding && lastItem != null){
                    holdTime += Time.delta();

                    if(holdTime >= holdWithdraw){
                        int amount = Math.min(tile.entity.items.get(lastItem), player.maxAccepted(lastItem));
                        Call.requestItem(player, tile, lastItem, amount);
                        holding = false;
                        holdTime = 0f;

                        if(net.client()) Events.fire(new WithdrawEvent(tile, player, lastItem, amount));
                    }
                }

                updateTablePosition();
                if(tile.block().hasItems){
                    for(int i = 0; i < content.items().size; i++){
                        boolean has = tile.entity.items.has(content.item(i));
                        if(has != container.contains(i)){
                            rebuild(false);
                        }
                    }
                }
            }
        });

        int cols = 3;
        int row = 0;

        table.margin(4f);
        table.defaults().size(8 * 5).pad(4f);

        if(tile.block().hasItems){

            for(int i = 0; i < content.items().size; i++){
                Item item = content.item(i);
                if(!tile.entity.items.has(item)) continue;

                container.add(i);

                Boolp canPick = () -> player.acceptsItem(item) && !state.isPaused();

                HandCursorListener l = new HandCursorListener();
                l.setEnabled(canPick);

                Element image = itemImage(item.icon(Cicon.xlarge), () -> {
                    if(tile == null || tile.entity == null){
                        return "";
                    }
                    return round(tile.entity.items.get(item));
                });
                image.addListener(l);

                image.addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        if(!canPick.get() || tile == null || tile.entity == null || tile.entity.items == null || !tile.entity.items.has(item)) return false;
                        int amount = Math.min(1, player.maxAccepted(item));
                        if(amount > 0){
                            Call.requestItem(player, tile, item, amount);
                            lastItem = item;
                            holding = true;
                            holdTime = 0f;
                            if(net.client()) Events.fire(new WithdrawEvent(tile, player, item, amount));
                        }
                        return true;
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                        holding = false;
                        lastItem = null;
                    }
                });
                table.add(image);

                if(row++ % cols == cols - 1) table.row();
            }
        }

        if(row == 0){
            table.setSize(0f, 0f);
        }

        System.out.println("Tile: " + tile.entity.id + " Core: " + player.getClosestCore().id);

        updateTablePosition();

        table.visible(true);

        if(actions){
            table.setScale(0f, 1f);
            table.actions(Actions.scaleTo(1f, 1f, 0.07f, Interpolation.pow3Out));
        }else{
            table.setScale(1f, 1f);
        }
    }

    private String round(float f){
        f = (int)f;
        if(f >= 1000000){
            return (int)(f / 1000000f) + "[gray]" + Core.bundle.getOrNull("unit.millions") + "[]";
        }else if(f >= 1000){
            return (int)(f / 1000) + Core.bundle.getOrNull("unit.thousands");
        }else{
            return (int)f + "";
        }
    }

    private void updateTablePosition(){
        Vec2 v = Core.input.mouseScreen(tile.drawx() + tile.block().size * tilesize / 2f, tile.drawy() + tile.block().size * tilesize / 2f);
        table.pack();
        table.setPosition(v.x, v.y, Align.topLeft);
    }

    private Element itemImage(TextureRegion region, Prov<CharSequence> text){
        Stack stack = new Stack();

        Table t = new Table().left().bottom();
        t.label(text);

        stack.add(new Image(region));
        stack.add(t);
        return stack;
    }

    private void rebuildCore(boolean actions){
        Table coreTable = new Table();
        IntSet container = new IntSet();

        coreTable.background(Tex.inventory);
        coreTable.touchable(Touchable.disabled);

        int cols = 5;
        int row = 0;

        coreTable.margin(4f);
        coreTable.defaults().size(8 * 5).pad(4f);
        Tile core = state.teams.get(player.getTeam()).cores.get(0).tile;
        if(core.block().hasItems){

            for(int i = 0; i < content.items().size; i++){
                Item item = content.item(i);
                if(i == 4 || i == 5 || i == 8 || i == 13 || i == 14 || i ==15) continue;
                container.add(i);

                Element image = itemImage(item.icon(Cicon.xlarge), () -> {
                    if(core == null || core.entity == null){
                        return "";
                    }
                    return round(core.entity.items.get(item));
                });
                coreTable.add(image);

                if(row++ % cols == cols - 1) coreTable.row();
            }
        }

        if(row == 0){
            coreTable.setSize(0f, 0f);
        }

        System.out.println("Tile: " + tile.entity.id + " Core: " + player.getClosestCore().id);

        coreTable.visible(true);

        if(actions){
            coreTable.setScale(0f, 1f);
            coreTable.actions(Actions.scaleTo(1f, 1f, 0.07f, Interpolation.pow3Out));
        }else{
            coreTable.setScale(1f, 1f);
        }
        ui.hudfrag.coreTable.clearChildren();
        ui.hudfrag.coreTable.add(coreTable);

//        // freash eco table
//        for(int i = 0; i < content.items().size; i++) {
//            if (i == 4 || i == 5 || i == 8 || i == 13 || i == 14 || i == 15) continue;
//            Item item = content.item(i);
//            coreStorage[i] = core.entity.items.get(item);
//        }
//        hasEcoTable = false;
//        updateEcoTable();
    }
//
//    private float[] coreStorage = new float[15];
//    private boolean hasEcoTable = false;
//    private float sleep = 0;
//    private void updateEcoTable() {
//        if(state.teams.get(player.getTeam()) == null) {
//            // match does not start
//            return;
//        }
//        if(!hasEcoTable) {
//            // reset all vars
//            sleep = 0;
//            // coreStorage = new float[15];
//            hasEcoTable = true;
//        }
//        if(sleep % 20 != 0) {
//            sleep ++;
//            return;
//        }
//        IntSet container = new IntSet();
//        Table ecoTable = new Table();
//        ecoTable.background(Tex.inventory);
//        ecoTable.touchable(Touchable.disabled);
//
//        int cols = 5;
//        int row = 0;
//
//        ecoTable.margin(4f);
//        ecoTable.defaults().size(8 * 5).pad(4f);
//        Tile core = state.teams.get(player.getTeam()).cores.get(0).tile;
//        for (int i = 0; i < content.items().size; i++) {
//            Item item = content.item(i);
//            if (i == 4 || i == 5 || i == 8 || i == 13 || i == 14 || i == 15) continue;
//            container.add(i);
//            // update the new core value
//            coreStorage[i] = coreStorage[i] - core.entity.items.get(item);
//            float value = coreStorage[i];
//            Element image = itemImage(item.icon(Cicon.xlarge), () -> {
//                if(core == null || core.entity == null){
//                    return "";
//                }
//                return round(value);
//            });
//            ecoTable.add(image);
//            if(row++ % cols == cols - 1) ecoTable.row();
//        }
//        if(row == 0){
//            ecoTable.setSize(0f, 0f);
//        }
//
//        ecoTable.visible(true);
//
//        ecoTable.setScale(0f, 1f);
//        ecoTable.actions(Actions.scaleTo(1f, 1f, 0.07f, Interpolation.pow3Out));
//        ui.hudfrag.ecoTable.clearChildren();
//        ui.hudfrag.ecoTable.add(ecoTable);
//    }
}
