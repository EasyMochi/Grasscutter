package emu.grasscutter.scripts.lua_engine.service;

import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.listener.ScriptMonsterListener;

import java.util.ArrayList;
import java.util.List;

public class ScriptMonsterSpawnService {

    private final SceneScriptManager sceneScriptManager;
    public final List<ScriptMonsterListener> onMonsterCreatedListener = new ArrayList<>();

    public final List<ScriptMonsterListener> onMonsterDeadListener = new ArrayList<>();

    public ScriptMonsterSpawnService(SceneScriptManager sceneScriptManager){
        this.sceneScriptManager = sceneScriptManager;
    }

    public void addMonsterCreatedListener(ScriptMonsterListener scriptMonsterListener){
        onMonsterCreatedListener.add(scriptMonsterListener);
    }
    public void addMonsterDeadListener(ScriptMonsterListener scriptMonsterListener){
        onMonsterDeadListener.add(scriptMonsterListener);
    }
    public void removeMonsterCreatedListener(ScriptMonsterListener scriptMonsterListener){
        onMonsterCreatedListener.remove(scriptMonsterListener);
    }
    public void removeMonsterDeadListener(ScriptMonsterListener scriptMonsterListener){
        onMonsterDeadListener.remove(scriptMonsterListener);
    }
    public void onMonsterDead(EntityMonster entityMonster){
        onMonsterDeadListener.forEach(l -> l.onNotify(entityMonster));
    }

}
