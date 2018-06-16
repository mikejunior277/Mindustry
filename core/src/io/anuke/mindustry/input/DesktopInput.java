package io.anuke.mindustry.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.NetEvents;
import io.anuke.mindustry.util.StampUtil;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.blocks.types.LogicAcceptor;
import io.anuke.ucore.core.Graphics;
import io.anuke.ucore.core.Inputs;
import io.anuke.ucore.core.Inputs.DeviceType;
import io.anuke.ucore.core.KeyBinds;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.scene.utils.Cursors;
import io.anuke.ucore.util.Input;
import io.anuke.ucore.util.Mathf;

import java.io.IOException;

import static io.anuke.mindustry.Vars.*;

public class DesktopInput extends InputHandler{
	float mousex, mousey;
	float endx, endy;
	private boolean enableHold = false;
	private boolean beganBreak;
	public boolean linking,placingStamp,stamping;
	private Tile linkTile;
	private boolean rotated = false, rotatedAlt, zoomed;
	
	@Override public float getCursorEndX(){ return endx; }
	@Override public float getCursorEndY(){ return endy; }
	@Override public float getCursorX(){ return Graphics.screen(mousex, mousey).x; }
	@Override public float getCursorY(){ return Gdx.graphics.getHeight() - 1 - Graphics.screen(mousex, mousey).y; }
	@Override public boolean drawPlace(){ return !beganBreak; }

	@Override
	public void update() {
		if (player.isDead()) return;

		if (Inputs.keyRelease("select")) {
			placeMode.released(getBlockX(), getBlockY(), getBlockEndX(), getBlockEndY());
		}

		if (Inputs.keyRelease("break") && !beganBreak) {
			breakMode.released(getBlockX(), getBlockY(), getBlockEndX(), getBlockEndY());
		}

		if ((Inputs.keyTap("select") && recipe != null) || Inputs.keyTap("break")) {
			Vector2 vec = Graphics.world(Gdx.input.getX(), Gdx.input.getY());

			mousex = vec.x;
			mousey = vec.y;
		}

		if(!Inputs.keyDown("select") && !Inputs.keyDown("break")){
			mousex = Graphics.mouseWorld().x;
			mousey = Graphics.mouseWorld().y;
		}

		endx = Gdx.input.getX();
		endy = Gdx.input.getY();

		boolean controller = KeyBinds.getSection("default").device.type == DeviceType.controller;

		if (Inputs.getAxisActive("zoom") && (Inputs.keyDown("zoom_hold") || controller)
				&& !state.is(State.menu) && !ui.hasDialog()) {
			if ((!zoomed || !controller)) {
				renderer.scaleCamera((int) Inputs.getAxis("zoom"));
			}
			zoomed = true;
		} else {
			zoomed = false;
		}

		if (!rotated) {
			rotation += Inputs.getAxis("rotate_alt");
			rotated = true;
		}
		if (!Inputs.getAxisActive("rotate_alt")) rotated = false;

		if (!rotatedAlt) {
			rotation += Inputs.getAxis("rotate");
			rotatedAlt = true;
		}
		if (!Inputs.getAxisActive("rotate")) rotatedAlt = false;

		rotation = Mathf.mod(rotation, 4);

		if (Inputs.keyDown("break")) {
			breakMode = PlaceMode.areaDelete;
		} else {
			breakMode = PlaceMode.hold;
		}

		for (int i = 1; i <= 6 && i <= control.upgrades().getWeapons().size; i++) {
			if (Inputs.keyTap("weapon_" + i)) {
				player.weaponLeft = player.weaponRight = control.upgrades().getWeapons().get(i - 1);
				if (Net.active()) NetEvents.handleWeaponSwitch();
				ui.hudfrag.updateWeapons();
			}
		}

		Tile cursor = world[player.dimension].tile(tilex(), tiley());
		Tile target = cursor == null ? null : cursor.isLinked() ? cursor.getLinked() : cursor;
		boolean showCursor = false;

		if (recipe != null) {
			preview.first().set(cursor.worldx(),cursor.worldy());
			if(recipe.result.name() != preview.first().parentBlock.name()) {
				preview.first().parentBlock = recipe.result;
				preview.first().rotation = 0f;
			}
			if(rotation*90!=preview.first().rotation&&recipe.result.rotate)
				preview.first().rotation = rotation*90;
		}

		if(recipe == null && !preview.first().hidden)
			preview.first().hidden = true;
		if(recipe != null && preview.first().hidden)
			preview.first().hidden = false;
		if(preview.first().getGroup()==null)
			world[player.dimension].previewGroup.add(preview.first());

		if (recipe == null && target != null && !ui.hasMouse() && Inputs.keyDown("block_info")
				&& target.block().fullDescription != null) {
			showCursor = true;
			if(Inputs.keyTap("select")){
			    ui.hudfrag.blockfrag.showBlockInfo(target.block());
                Cursors.restoreCursor();
            }
		}
		
		if(recipe == null && !ui.hasMouse() && Inputs.keyDown("block_logs")) {
			showCursor = true;
			if(Inputs.keyTap("select")){
				NetEvents.handleBlockLogRequest(getBlockX(), getBlockY());
				Timers.runTask(20f, () -> {
					ui.hudfrag.blockfrag.showBlockLogs(getBlockX(), getBlockY());
					Cursors.restoreCursor();
				});
			}
		}

        if(target != null && target.block().isConfigurable(target)){
		    showCursor = true;
        }
		
		if(target != null && Inputs.keyTap("select") && !ui.hasMouse()){
			if(target.block().isConfigurable(target)){
				ui.configfrag.showConfig(target);
			} else if (!ui.configfrag.hasConfigMouse()) {
				ui.configfrag.hideConfig();
			}

			target.block().tapped(target);
			if (Net.active()) NetEvents.handleBlockTap(target);
		}

		if (Inputs.keyTap("break")) {
			ui.configfrag.hideConfig();
		}

		if (Inputs.keyRelease("break")) {
			beganBreak = false;
		}

		if (recipe != null && Inputs.keyTap("break")) {
			beganBreak = true;
			recipe = null;
		}

		//block breaking
		if (enableHold && Inputs.keyDown("break") && cursor != null && validBreak(tilex(), tiley())) {
			breaktime += Timers.delta();
			if (breaktime >= cursor.getBreakTime()) {
				breakBlock(cursor.x, cursor.y, true);
				breaktime = 0f;
			}
		} else {
			breaktime = 0f;
		}

		if (recipe != null) {
			showCursor = validPlace(tilex(), tiley(), control.input().recipe.result) && control.input().cursorNear();
		}

		if (!ui.hasMouse()) {
			if (showCursor)
				Cursors.setHand();
			else
				Cursors.restoreCursor();
		}

		if (Inputs.keyDown(Input.C)&&Inputs.keyDown(Input.CONTROL_LEFT)) {
			stamping = true;
			stampOrigin.x = cursor.worldx();
			stampOrigin.y = cursor.worldy();
		}

		if (Inputs.keyDown(Input.V)&&Inputs.keyDown(Input.CONTROL_LEFT)&&stamp!=null) {
		    placingStamp = true;
		}

		if(Inputs.buttonDown(Input.MOUSE_RIGHT.code)&&(placingStamp||stamping)){
			placingStamp = false;
			stamping = false;
		}

		if(Inputs.buttonDown(Input.MOUSE_LEFT.code)&&placingStamp){
		    StampUtil.loadStamp(cursor.x,cursor.y,stamp,player.dimension);
		    placingStamp=false;
        	}

		if(Inputs.buttonDown(Input.MOUSE_LEFT.code)&&stamping){
			Tile tile = world[player.dimension].tileWorld(stampOrigin.x,stampOrigin.y);
			stamp = StampUtil.createStamp(tile.x,tile.y,cursor.x+1-tile.x, cursor.y+1-tile.y ,player.dimension);
			try{StampUtil.writeStampFile("temp",stamp);}catch (IOException e) {e.printStackTrace();}
			stamping=false;
		}

		if (Inputs.keyTap(Input.O)) {
			ui.stampChooser.show();
		}

		if (!ui.chatfrag.chatOpen()) {
			if (Inputs.keyTap("logic_link")) {
				if(cursor.block() instanceof LogicAcceptor) {
					LogicAcceptor block = (LogicAcceptor) cursor.block();
					if (!linking && block.canLogicOutput(cursor)) {
						linking = true;
						linkTile = cursor;
					}
					else if (linking) {
						if(linkTile == cursor || !(linkTile.block() instanceof LogicAcceptor))
							linking = false;
						else {
							linking = false;
							((LogicAcceptor) linkTile.block()).logicLink(linkTile, cursor);
						}
					}
				}
			}
			if (Inputs.keyRelease("ship_mode") && !player.isFlying && player.flyCooldown <= 0 && global.bossAmount <= 0) {
				player.flyCooldown = 100;
				player.isFlying = true;
			} else if (Inputs.keyRelease("ship_mode") && player.isFlying && player.flyCooldown <= 0) {
				player.flyCooldown = 100;
				player.isFlying = false;
			}
		}
	}

	public int tilex(){
		return (recipe != null && recipe.result.isMultiblock() &&
				recipe.result.size % 2 == 0) ?
				Mathf.scl(Graphics.mouseWorld().x, tilesize) : Mathf.scl2(Graphics.mouseWorld().x, tilesize);
	}

	public int tiley(){
		return (recipe != null && recipe.result.isMultiblock() &&
				recipe.result.size % 2 == 0) ?
				Mathf.scl(Graphics.mouseWorld().y, tilesize) : Mathf.scl2(Graphics.mouseWorld().y, tilesize);
	}

	@Override
	public boolean keyDown(int keycode) {
		return super.keyDown(keycode);
	}
}
