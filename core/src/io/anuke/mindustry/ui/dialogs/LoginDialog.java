package io.anuke.mindustry.ui.dialogs;

import io.anuke.arc.Core;
import io.anuke.arc.input.KeyCode;
import io.anuke.arc.scene.event.Touchable;
import io.anuke.arc.scene.ui.Label;
import io.anuke.arc.scene.ui.Label.LabelStyle;
import io.anuke.arc.scene.ui.TextField;
import io.anuke.arc.util.Align;
import io.anuke.arc.util.Log;
import io.anuke.mindustry.net.Authentication;
import io.anuke.mindustry.net.Authentication.LoginInfo;

import static io.anuke.mindustry.Vars.*;

public class LoginDialog extends FloatingDialog {
    public LoginDialog() {
        super("$login.title");

        keyDown(key -> {
            if(key == KeyCode.ESCAPE || key == KeyCode.BACK){
                cancel();
            }
        });

        shown(this::setup);
    }

    public void cancel(){
        ui.loadfrag.hide();
        netClient.disconnectQuietly();
        auth.loginInfo = null;
        hide();
    }

    public void submit(){
        LoginInfo info = auth.loginInfo;
        if(info.username == null || info.password == null || info.username.isEmpty() || info.password.isEmpty()){
            ui.showInfo("$login.invalidcredentials");
            return;
        }
        ui.loadfrag.show();
        auth.doLogin(info.authServer, info.username, info.password).done(response -> {
            ui.loadfrag.hide();
            if(response.success){
                hide();
                if(info.successCallback != null) info.successCallback.run();
            }else{
                switch(response.errorCode){
                    case "INVALID_CREDENTIALS": {
                        ui.showInfo("$login.invalidcredentials");
                        break;
                    }
                    case "ACCOUNT_DISABLED": {
                        ui.showInfo("$login.accountdisabled");
                        break;
                    }
                    default:
                        Authentication.showApiError(response);
                }
            }
        });
    }

    public void setup(){
        if(auth.loginInfo == null){
            throw new RuntimeException("Login dialog was shown but no login is active!");
        }
        LoginInfo info = auth.loginInfo;

        cont.clear();
        cont.marginRight(50f).marginLeft(50f);
        cont.add(Core.bundle.format("login.info", info.authServer)).wrap().growX().get().setAlignment(Align.center);
        cont.row();
        if(info.loginNotice != null){
            cont.add(Core.bundle.format("login.notice", info.loginNotice)).wrap().fillX().get().setAlignment(Align.center);
        }
        cont.row();
        cont.table(t -> {
            t.add("$login.username").padRight(10);
            TextField usernameField = t.addField("", text -> auth.loginInfo.username = text).size(320f, 54f).get();
            usernameField.next(false);
            platform.addDialog(usernameField, 50);
            t.row();
            t.add("$login.password").padRight(10);
            TextField passwordField = t.addField("", text -> auth.loginInfo.password = text).size(320f, 54f).get();
            passwordField.setPasswordMode(true);
            passwordField.setPasswordCharacter('•');
            platform.addDialog(passwordField, 50);
        }).padTop(30f).padBottom(30f);
        cont.row();
        cont.table(t -> {
            t.defaults().size(140f, 60f).pad(4f);
            t.addButton("$cancel", this::cancel);
            t.addButton("$login.submit", this::submit);
        }).padBottom(20f);
        if(info.showRegister){
            cont.row();
            Label registerLink = cont.add("$login.register").get();
            registerLink.touchable(Touchable.enabled);
            registerLink.tapped(() -> {
                if(!Core.net.openURI(info.authServer)){
                    ui.showErrorMessage("$linkfail");
                    Core.app.setClipboardText(info.authServer);
                }
            });
        }
    }
}