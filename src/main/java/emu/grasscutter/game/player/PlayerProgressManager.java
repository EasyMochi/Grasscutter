package emu.grasscutter.game.player;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.excels.OpenStateData;
import emu.grasscutter.data.excels.OpenStateData.OpenStateCondType;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestCond;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.server.packet.send.PacketOpenStateChangeNotify;
import emu.grasscutter.server.packet.send.PacketOpenStateUpdateNotify;
import emu.grasscutter.server.packet.send.PacketSceneAreaUnlockNotify;
import emu.grasscutter.server.packet.send.PacketScenePointUnlockNotify;
import emu.grasscutter.server.packet.send.PacketSetOpenStateRsp;
import lombok.val;
import org.anime_game_servers.core.gi.enums.QuestState;
import org.anime_game_servers.gi_lua.models.ScriptArgs;
import org.anime_game_servers.multi_proto.gi.messages.general.Retcode;

import java.util.*;
import java.util.stream.Collectors;

import static org.anime_game_servers.gi_lua.models.constants.EventType.EVENT_UNLOCK_TRANS_POINT;

// @Entity
public class PlayerProgressManager extends BasePlayerDataManager {
    public PlayerProgressManager(Player player) {
        super(player);
    }

    /**********
        Handler for player login.
    **********/
    public void onPlayerLogin() {
        // Try unlocking open states on player login. This handles accounts where unlock conditions were
        // already met before certain open state unlocks were implemented.
        this.tryUnlockOpenStates(false);

        // Send notify to the client.
        player.getSession().send(new PacketOpenStateUpdateNotify(this.player));

        // Add statue quests if necessary.
        this.addStatueQuestsOnLogin();

        // Auto-unlock the first statue and map area, until we figure out how to make
        // that particular statue interactable.
        this.player.getUnlockedScenePoints(3).add(7);
        this.player.getUnlockedSceneAreas(3).add(1);

        // add replacement costumes if necessary
        this.addReplaceCostumes();

    }

    /******************************************************************************************************************
     ******************************************************************************************************************
     * OPEN STATES
     ******************************************************************************************************************
     *****************************************************************************************************************/

    // Set of open states that are never unlocked, whether they fulfill the conditions or not.
    public static final Set<Integer> BLACKLIST_OPEN_STATES = Set.of(
    48      // blacklist OPEN_STATE_LIMIT_REGION_GLOBAL to make Meledy happy. =D Remove this as soon as quest unlocks are fully implemented.
    );

    // Set of open states that are set per default for all accounts. Can be overwritten by an entry in `map`.
    public static final Set<Integer> DEFAULT_OPEN_STATES = GameData.getOpenStateList().stream()
        .filter(s ->
            s.isDefaultState() && !s.isAllowClientOpen() // Actual default-opened states.
            || ((s.getCond().size() == 1)
                && (s.getCond().get(0).getCondType() == OpenStateCondType.OPEN_STATE_COND_PLAYER_LEVEL)
                && (s.getCond().get(0).getParam() == 1))
            // All states whose unlock we don't handle correctly yet.
            || (s.getCond().stream().anyMatch(c -> c.getCondType() == OpenStateCondType.
                    OPEN_STATE_OFFERING_LEVEL || c.getCondType() == OpenStateCondType.
                OPEN_STATE_CITY_REPUTATION_LEVEL))
            // Always unlock OPEN_STATE_PAIMON, otherwise the player will not have a working chat.
            || s.getId() == 1
        )
        .filter(s -> !BLACKLIST_OPEN_STATES.contains(s.getId()))    // Filter out states in the blacklist.
        .map(OpenStateData::getId)
        .collect(Collectors.toSet());

    /**********
        Direct getters and setters for open states.
    **********/
    public int getOpenState(int openState) {
        return this.player.getOpenStates().getOrDefault(openState, 0);
    }

    private void setOpenState(int openState, int value, boolean sendNotify) {
        int previousValue = this.player.getOpenStates().getOrDefault(openState, 0);

        if (value != previousValue) {
            this.player.getOpenStates().put(openState, value);

            this.player.getQuestManager().queueEvent(QuestCond.QUEST_COND_OPEN_STATE_EQUAL, openState, value);

            if (sendNotify) {
                player.getSession().send(new PacketOpenStateChangeNotify(openState, value));
            }
        }
    }
    private void setOpenState(int openState, int value) {
        this.setOpenState(openState, value, true);
    }

    /**********
        Condition checking for setting open states.
    **********/
    private boolean areConditionsMet(OpenStateData openState) {
        // Check all conditions and test if at least one of them is violated.
        for (val condition : openState.getCond()) {
            switch (condition.getCondType()){
                // For level conditions, check if the player has reached the necessary level.
                case OPEN_STATE_COND_PLAYER_LEVEL -> {
                    if (this.player.getLevel() < condition.getParam()) {
                        return false;
                    }
                }
                case OPEN_STATE_COND_QUEST -> {
                    // check sub quest id for quest finished met requirements
                    val quest = this.player.getQuestManager().getQuestById(condition.getParam());
                    if (quest == null
                        || quest.getState() != QuestState.QUEST_STATE_FINISHED){
                        return false;
                    }
                }
                case OPEN_STATE_COND_PARENT_QUEST -> {
                    // check main quest id for quest finished met requirements
                    // TODO not sure if its having or finished quest
                    val mainQuest = this.player.getQuestManager().getMainQuestById(condition.getParam());
                    if (mainQuest == null
                        || mainQuest.getState() != ParentQuestState.PARENT_QUEST_STATE_FINISHED){
                        return false;
                    }
                }
                // ToDo: Implement.
                case OPEN_STATE_CITY_REPUTATION_LEVEL -> {
                    // not implemented yet, opening them all seems to trigger the mine craft quest
                    // param seems to be the city id
                    // param2 seems to be the reputation level
                    return false;
                }
                // ToDo: Implement.
                case OPEN_STATE_OFFERING_LEVEL -> {
                    // param offering id
                    // param2 offering level
                }
            }
        }

        // Done. If we didn't find any violations, all conditions are met.
        return true;
    }

    /**********
        Setting open states from the client (via `SetOpenStateReq`).
    **********/
    public void setOpenStateFromClient(int openState, int value) {
        // Get the data for this open state.
        OpenStateData data = GameData.getOpenStateDataMap().get(openState);
        if (data == null) {
            this.player.sendPacket(new PacketSetOpenStateRsp(Retcode.RET_FAIL));
            return;
        }

        // Make sure that this is an open state that the client is allowed to set,
        // and that it doesn't have any further conditions attached.
        if (!data.isAllowClientOpen() || !this.areConditionsMet(data)) {
            this.player.sendPacket(new PacketSetOpenStateRsp(Retcode.RET_FAIL));
            return;
        }

        // Set.
        this.setOpenState(openState, value);
        this.player.sendPacket(new PacketSetOpenStateRsp(openState, value));
    }

    /**
     * This force sets an open state, ignoring all conditions and permissions
     */
    public void forceSetOpenState(int openState, int value){
        setOpenState(openState, value);
    }

    /**********
        Triggered unlocking of open states (unlock states whose conditions have been met.)
    **********/
    public void tryUnlockOpenStates(boolean sendNotify) {
        // Get list of open states that are not yet unlocked.
        val lockedStates = GameData.getOpenStateList().stream()
            .filter(s -> this.player.getOpenStates().getOrDefault(s.getId(), 0) == 0)
            .toList();

        // Try unlocking all of them.
        for (val state : lockedStates) {
            // TODO probably better to build similar structure as quest handler
            // so that it doesnt have to loop through all the states and check
            // To auto-unlock a state, it has to meet three conditions:
            // * it can not be a state that is unlocked by the client,
            // * it has to meet all its unlock conditions, and
            // * it can not be in the blacklist.
            if (!state.isAllowClientOpen() && this.areConditionsMet(state) && !BLACKLIST_OPEN_STATES.contains(state.getId())) {
                this.setOpenState(state.getId(), 1, sendNotify);
            }
        }
    }
    public void tryUnlockOpenStates() {
        this.tryUnlockOpenStates(true);
    }

    /******************************************************************************************************************
     ******************************************************************************************************************
     * MAP AREAS AND POINTS
     ******************************************************************************************************************
     *****************************************************************************************************************/
    private void addStatueQuestsOnLogin() {
        // Get all currently existing subquests for the "unlock all statues" main quest.
        var statueMainQuest = GameData.getMainQuestDataMap().get(303);
        var statueSubQuests = statueMainQuest.getSubQuests();

        // Add the main statue quest if it isn't active yet.
        var statueGameMainQuest = this.player.getQuestManager().getMainQuestById(303);
        if (statueGameMainQuest == null) {
            this.player.getQuestManager().addQuest(30302);
            statueGameMainQuest = this.player.getQuestManager().getMainQuestById(303);
        }

        // Set all subquests to active if they aren't already finished.
        for (var subData : statueSubQuests) {
            var subGameQuest = statueGameMainQuest.getChildQuestById(subData.getSubId());
            if (subGameQuest != null && subGameQuest.getState() == QuestState.QUEST_STATE_UNSTARTED) {
                this.player.getQuestManager().addQuest(subGameQuest.getQuestData());
            }
        }
    }

    public boolean unlockTransPoint(int sceneId, int pointId, boolean isStatue) {
        // Check whether the unlocked point exists and whether it is still locked.
        ScenePointEntry scenePointEntry = GameData.getScenePointEntryById(sceneId, pointId);

        if (scenePointEntry == null || this.player.getUnlockedScenePoints(sceneId).contains(pointId)) {
            return false;
        }

        // Add the point to the list of unlocked points for its scene.
        this.player.getUnlockedScenePoints(sceneId).add(pointId);

        // Give primogems  and Adventure EXP for unlocking.
        this.player.getInventory().addItem(201, 5, ActionReason.UnlockPointReward);
        this.player.getInventory().addItem(102, isStatue ? 50 : 10, ActionReason.UnlockPointReward);
        // TODO use TransPointREwardConfigData

        // this.player.sendPacket(new PacketPlayerPropChangeReasonNotify(this.player.getProperty(PlayerProperty.PROP_PLAYER_EXP), PlayerProperty.PROP_PLAYER_EXP, PropChangeReason.PROP_CHANGE_REASON_PLAYER_ADD_EXP));

        // Fire quest and script trigger for trans point unlock.
        this.player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_UNLOCK_TRANS_POINT, sceneId, pointId);
        this.player.getScene().getScriptManager().callEvent(new ScriptArgs(0, EVENT_UNLOCK_TRANS_POINT, sceneId, pointId));

        // Send packet.
        this.player.sendPacket(new PacketScenePointUnlockNotify(sceneId, pointId));
        return true;
    }

    public boolean lockTransPoint(int sceneId, int pointId) {
        val scenePointEntry = GameData.getScenePointEntryById(sceneId, pointId);

        if (scenePointEntry == null || !this.player.getUnlockedScenePoints(sceneId).contains(pointId)) {
            return false;
        }

        this.player.getUnlockedScenePoints(sceneId).remove(pointId);

        this.player.sendPacket(new PacketScenePointUnlockNotify(sceneId, pointId, true));
        return true;
    }

    public void unlockSceneArea(int sceneId, int areaId) {
        // Add the area to the list of unlocked areas in its scene.
        this.player.getUnlockedSceneAreas(sceneId).add(areaId);

        // Send packet.
        this.player.sendPacket(new PacketSceneAreaUnlockNotify(sceneId, areaId));
        this.player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_UNLOCK_AREA, sceneId, areaId);
    }

    /**
     * Give replace costume to player (Ambor, Jean, Mona, Rosaria)
     */
    public void addReplaceCostumes(){
        val currentPlayerCostumes = player.getCostumeList();
        GameData.getAvatarReplaceCostumeDataMap().keySet().forEach(costumeId -> {
            if (GameData.getAvatarCostumeDataMap().get(costumeId) == null || currentPlayerCostumes.contains(costumeId)){
                return;
            }
            this.player.addCostume(costumeId);
        });
    }

    /**
     * Quest progress
     */

    public void addQuestProgress(int id, int count){
        val newCount = player.getPlayerProgress().addToCurrentProgress(id, count);
        player.save();
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS, id, newCount);
    }

    /**
     * Item history
     */
    public void addItemObtainedHistory(int id, int count){
        val newCount = player.getPlayerProgress().addToItemHistory(id, count);
        player.save();
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_HISTORY_GOT_ANY_ITEM, id, newCount);
    }
}
