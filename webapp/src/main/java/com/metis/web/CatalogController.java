package com.metis.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.GameState;
import com.metis.engine.api.BattleRepository;
import com.metis.engine.api.BattleTemplateRepository;
import com.metis.engine.api.BattleRepository.BattleSessionDetail;
import com.metis.engine.api.DefinitionRepository;
import com.metis.engine.api.DefinitionRepository.DefinitionVersion;
import com.metis.engine.api.AuthenticationContext;
import com.metis.engine.api.MetisUser;
import com.metis.web.CatalogRepository.FactionPage;
import com.metis.web.CatalogRepository.GameObjectPage;
import com.metis.web.CatalogRepository.GameSummary;
import com.metis.web.CatalogRepository.UnitTypePage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
public class CatalogController {

    private final CatalogRepository catalog;
    private final BattleRepository battle;
    private final BattleTemplateRepository templates;
    private final DefinitionRepository definitions;
    private final ObjectMapper objectMapper;
    private final AuthenticationContext authentication;

    public CatalogController(CatalogRepository catalog, BattleRepository battle,
                              BattleTemplateRepository templates, DefinitionRepository definitions,
                              ObjectMapper objectMapper,
                              AuthenticationContext authentication) {
        this.catalog = catalog;
        this.battle = battle;
        this.templates = templates;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
        this.authentication = authentication;
    }

    @GetMapping("/")
    public String home() {
        return "home";
    }

    @GetMapping("/design-options")
    public String designOptions() {
        return "design-options";
    }

    @GetMapping("/games")
    public String games(Model model) {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        List<GameSummary> games = catalog.findGames();
        model.addAttribute("games", games);
        model.addAttribute("fixtureGameId", games.isEmpty() ? null : games.get(0).gameId());
        return "games";
    }

    @GetMapping("/admin/engine-logs")
    public String engineLogs(Model model) {
        model.addAttribute("sessions", battle.findSessions(null, null));
        return "engine-logs";
    }

    @GetMapping("/admin/engine-logs/sessions/{sessionId}")
    @ResponseBody
    public List<BattleRepository.EngineLog> engineLogs(@PathVariable long sessionId,
                                                       @RequestParam(defaultValue = "0") long afterLogId) {
        if (battle.findSession(sessionId).isEmpty()) {
            throw notFound();
        }
        return battle.findEngineLogs(sessionId, afterLogId);
    }

    @GetMapping("/simulator")
    public String simulator(Model model, HttpServletRequest request) {
        MetisUser user = authentication.require(request);
        model.addAttribute("games", catalog.findGames());
        model.addAttribute("runningGames", battle.findVisibleSessions(null, "running", user));
        model.addAttribute("oldGames", battle.findVisibleSessions(null, null, user).stream()
                .filter(session -> !"running".equals(session.status())).toList());
        return "simulator";
    }

    @GetMapping("/simulator/games/{gameId}")
    public String simulatorGame(@PathVariable long gameId, Model model) {
        GameSummary game = catalog.findGame(gameId).orElseThrow(this::notFound);
        model.addAttribute("game", game);
        model.addAttribute("gameModes", catalog.findGameModes(gameId));
        model.addAttribute("scenarios", catalog.findScenarios(gameId));
        model.addAttribute("fleetFormats", catalog.findFleetFormats(gameId));
        model.addAttribute("factions", catalog.findFactions(gameId));
        model.addAttribute("boards", catalog.findBoards(gameId));
        model.addAttribute("templates", templates.findTemplates(gameId));
        return "simulator-game";
    }

    @GetMapping("/simulator/sessions/{sessionId}")
    public String simulatorSession(@PathVariable long sessionId, Model model) {
        BattleSessionDetail session = battle.findSession(sessionId).orElseThrow(this::notFound);
        long definitionId = session.gameVersionId() == null
                ? catalog.publishedDefinitionId(session.gameId()) : session.gameVersionId();
        model.addAttribute("battleSession", session);
        model.addAttribute("players", battle.findPlayers(sessionId));
        model.addAttribute("units", battle.findUnits(sessionId));
        model.addAttribute("fleets", battle.findFleets(sessionId));
        model.addAttribute("boards", battle.findBoards(sessionId));
        model.addAttribute("turns", battle.findTurns(sessionId));
        model.addAttribute("events", battle.findEvents(sessionId, 0));
        model.addAttribute("invitations", battle.findInvitations(sessionId));
        model.addAttribute("users", authentication.users().findActiveUsers());
        model.addAttribute("engineState", engineState(session));
        model.addAttribute("enginePhase", enginePhase(session));
        model.addAttribute("engineDefinition", definitions.findEngineDefinition(session.gameVersionId()).orElse(null));
        model.addAttribute("factions", catalog.findFactions(session.gameId(), session.gameVersionId()));
        model.addAttribute("scenarioName", catalog.findScenarios(session.gameId(), definitionId).stream()
                .filter(scenario -> session.scenarioId() != null && scenario.scenarioId() == session.scenarioId())
                .map(CatalogRepository.Scenario::name).findFirst().orElse("Standard Battle"));
        model.addAttribute("modeName", catalog.findGameModes(session.gameId(), definitionId).stream()
                .filter(mode -> session.gameModeId() != null && mode.gameModeId() == session.gameModeId())
                .map(CatalogRepository.GameMode::name).findFirst().orElse("Standard"));
        return "simulator-session";
    }

    @GetMapping({"/games/{gameId}", "/admin/games/{gameId}"})
    public String game(@PathVariable long gameId,
                       @RequestParam(required = false) Long versionId,
                       Model model) {
        List<DefinitionVersion> versions = definitions.findVersions(gameId);
        DefinitionVersion selectedVersion = selectVersion(gameId, versionId, versions);
        long selectedVersionId = selectedVersion.definitionId();
        GameSummary game = catalog.findGame(gameId, selectedVersionId).orElseThrow(this::notFound);
        model.addAttribute("game", game);
        model.addAttribute("factions", catalog.findFactions(gameId, selectedVersionId));
        model.addAttribute("terrain", catalog.findTerrain(gameId, selectedVersionId));
        model.addAttribute("rules", catalog.findRules(gameId, selectedVersionId));
        model.addAttribute("gameModes", catalog.findGameModes(gameId, selectedVersionId));
        model.addAttribute("phases", catalog.findPhases(gameId, selectedVersionId));
        model.addAttribute("actions", catalog.findActions(gameId, selectedVersionId));
        model.addAttribute("scenarios", catalog.findScenarios(gameId, selectedVersionId));
        model.addAttribute("boards", catalog.findBoards(gameId, selectedVersionId));
        model.addAttribute("fleetFormats", catalog.findFleetFormats(gameId, selectedVersionId));
        model.addAttribute("objectTypes", catalog.findObjectTypes(gameId, selectedVersionId));
        model.addAttribute("versions", versions);
        model.addAttribute("selectedVersion", selectedVersion);
        model.addAttribute("versionId", selectedVersionId);
        model.addAttribute("canEditGame", "draft".equals(selectedVersion.status()));
        return "game";
    }

    @PostMapping("/admin/games/{gameId}/definitions")
    public String createDefinition(@PathVariable long gameId,
                                   @RequestParam String code,
                                   @RequestParam String name,
                                   @RequestParam(required = false) String sourceRevision) {
        long definitionId = definitions.createVersion(gameId, code, name, sourceRevision);
        return "redirect:/admin/games/" + gameId + "?versionId=" + definitionId;
    }

    @PostMapping("/admin/definitions/{definitionId}/publish")
    public String publishDefinition(@PathVariable long definitionId) {
        DefinitionVersion version = definition(definitionId);
        definitions.publishVersion(definitionId);
        return "redirect:/admin/games/" + version.gameId() + "?versionId=" + definitionId;
    }

    @PostMapping("/admin/definitions/{definitionId}/retire")
    public String retireDefinition(@PathVariable long definitionId) {
        DefinitionVersion version = definition(definitionId);
        definitions.retireVersion(definitionId);
        return "redirect:/admin/games/" + version.gameId() + "?versionId=" + definitionId;
    }

    @GetMapping("/admin/games/{gameId}/edit")
    public String editGame(@PathVariable long gameId, @RequestParam long versionId, Model model) {
        requireDraftVersion(gameId, versionId);
        model.addAttribute("game", catalog.findGame(gameId, versionId).orElseThrow(this::notFound));
        model.addAttribute("versionId", versionId);
        return "edit-game";
    }

    @PostMapping("/admin/games/{gameId}/edit")
    public String updateGame(@PathVariable long gameId,
                             @RequestParam String gameName,
                             @RequestParam String gameDescription,
                             @RequestParam int minimumPlayers,
                             @RequestParam int maximumPlayers,
                             @RequestParam int minimumCpu,
                              @RequestParam int maximumCpu,
                              @RequestParam long versionId) {
        requireDraftVersion(gameId, versionId);
        definitions.updateGame(versionId, gameName, gameDescription, minimumPlayers,
                maximumPlayers, minimumCpu, maximumCpu);
        return "redirect:/admin/games/" + gameId + "?versionId=" + versionId + "&saved=true";
    }

    @GetMapping({"/games/{gameId}/objects", "/admin/games/{gameId}/objects"})
    public String objects(@PathVariable long gameId,
                          @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "") String q,
                          @RequestParam(required = false) Long versionId,
                          Model model) {
        List<DefinitionVersion> versions = definitions.findVersions(gameId);
        DefinitionVersion selectedVersion = selectVersion(gameId, versionId, versions);
        long selectedVersionId = selectedVersion.definitionId();
        GameSummary game = catalog.findGame(gameId, selectedVersionId).orElseThrow(this::notFound);
        model.addAttribute("game", game);
        model.addAttribute("objectTypes", catalog.findObjectTypes(gameId, selectedVersionId));
        model.addAttribute("selectedType", type);
        model.addAttribute("query", q);
        model.addAttribute("objects", catalog.findObjects(gameId, type, q, selectedVersionId));
        model.addAttribute("versions", versions);
        model.addAttribute("selectedVersion", selectedVersion);
        model.addAttribute("versionId", selectedVersionId);
        return "objects";
    }

    @GetMapping({"/game-objects/{objectId}", "/admin/game-objects/{objectId}"})
    public String object(@PathVariable long objectId, Model model) {
        GameObjectPage object = catalog.findObject(objectId).orElseThrow(this::notFound);
        model.addAttribute("object", object);
        model.addAttribute("selectedVersion", definition(object.getGameVersionId()));
        return "object";
    }

    @GetMapping("/admin/game-objects/{objectId}/edit")
    public String editObject(@PathVariable long objectId, Model model) {
        GameObjectPage object = catalog.findObject(objectId).orElseThrow(this::notFound);
        requireDraftObject(object);
        model.addAttribute("object", object);
        return "edit-object";
    }

    @PostMapping("/admin/game-objects/{objectId}/edit")
    public String updateObject(@PathVariable long objectId,
                               @RequestParam String objectName,
                               @RequestParam String objectDescription,
                               @RequestParam String displayText,
                               @RequestParam String objectData,
                               @RequestParam String sourceReference) {
        GameObjectPage object = catalog.findObject(objectId).orElseThrow(this::notFound);
        requireDraftObject(object);
        definitions.updateObject(object.getGameVersionId(), objectId, objectName, objectDescription,
                displayText, objectData.isBlank() ? "{}" : objectData,
                sourceReference.isBlank() ? "{}" : sourceReference);
        return "redirect:/admin/game-objects/" + objectId + "?saved=true";
    }

    @GetMapping("/factions/{factionId}")
    public String faction(@PathVariable long factionId, Model model) {
        FactionPage faction = catalog.findFaction(factionId).orElseThrow(this::notFound);
        model.addAttribute("faction", faction);
        return "faction";
    }

    @GetMapping("/unit-types/{unitTypeId}")
    public String unitType(@PathVariable long unitTypeId, Model model) {
        UnitTypePage unitType = catalog.findUnitType(unitTypeId).orElseThrow(this::notFound);
        model.addAttribute("unitType", unitType);
        return "unit-type";
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private DefinitionVersion definition(long definitionId) {
        return definitions.findVersion(definitionId).orElseThrow(this::notFound);
    }

    private void requireDraftVersion(long gameId, long versionId) {
        DefinitionVersion version = definitions.findVersion(versionId).orElseThrow(this::notFound);
        if (version.gameId() != gameId || !"draft".equals(version.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only the selected draft definition can be edited");
        }
    }

    private DefinitionVersion selectVersion(long gameId, Long versionId,
                                            List<DefinitionVersion> versions) {
        if (versionId == null) {
            return versions.stream()
                    .filter(version -> "published".equals(version.status()))
                    .findFirst().orElseThrow(this::notFound);
        }
        return versions.stream()
                .filter(version -> version.definitionId() == versionId)
                .findFirst().orElseThrow(this::notFound);
    }

    private void requireDraftObject(GameObjectPage object) {
        DefinitionVersion version = definition(object.getGameVersionId());
        if (!"draft".equals(version.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only objects in a draft definition can be edited");
        }
    }

    private String enginePhase(BattleSessionDetail session) {
        GameState state = engineState(session);
        return state == null ? "" : state.phaseCode();
    }

    private GameState engineState(BattleSessionDetail session) {
        if (session.state() == null || "{}".equals(session.state().trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(session.state(), GameState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored session state is invalid", exception);
        }
    }
}
