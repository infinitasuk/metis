package com.metis.web;

import com.metis.engine.api.BattleRepository;
import com.metis.engine.api.BattleRepository.BattleSessionDetail;
import com.metis.engine.api.DefinitionRepository;
import com.metis.engine.api.DefinitionRepository.DefinitionVersion;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
public class CatalogController {

    private final CatalogRepository catalog;
    private final BattleRepository battle;
    private final DefinitionRepository definitions;

    public CatalogController(CatalogRepository catalog, BattleRepository battle,
                             DefinitionRepository definitions) {
        this.catalog = catalog;
        this.battle = battle;
        this.definitions = definitions;
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
        model.addAttribute("games", catalog.findGames());
        return "games";
    }

    @GetMapping("/simulator")
    public String simulator(Model model) {
        model.addAttribute("games", catalog.findGames());
        return "simulator";
    }

    @GetMapping("/simulator/games/{gameId}")
    public String simulatorGame(@PathVariable long gameId, Model model) {
        GameSummary game = catalog.findGame(gameId).orElseThrow(this::notFound);
        model.addAttribute("game", game);
        model.addAttribute("gameModes", catalog.findGameModes(gameId));
        model.addAttribute("scenarios", catalog.findScenarios(gameId));
        model.addAttribute("fleetFormats", catalog.findFleetFormats(gameId));
        return "simulator-game";
    }

    @GetMapping("/simulator/sessions/{sessionId}")
    public String simulatorSession(@PathVariable long sessionId, Model model) {
        BattleSessionDetail session = battle.findSession(sessionId).orElseThrow(this::notFound);
        model.addAttribute("session", session);
        model.addAttribute("players", battle.findPlayers(sessionId));
        model.addAttribute("units", battle.findUnits(sessionId));
        model.addAttribute("fleets", battle.findFleets(sessionId));
        model.addAttribute("boards", battle.findBoards(sessionId));
        model.addAttribute("turns", battle.findTurns(sessionId));
        model.addAttribute("events", battle.findEvents(sessionId, 0));
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
        model.addAttribute("canEditGame", false);
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
    public String editGame(@PathVariable long gameId, Model model) {
        requireEditableDefinition(gameId);
        model.addAttribute("game", catalog.findGame(gameId).orElseThrow(this::notFound));
        return "edit-game";
    }

    @PostMapping("/admin/games/{gameId}/edit")
    public String updateGame(@PathVariable long gameId,
                             @RequestParam String gameName,
                             @RequestParam String gameDescription,
                             @RequestParam int minimumPlayers,
                             @RequestParam int maximumPlayers,
                             @RequestParam int minimumCpu,
                              @RequestParam int maximumCpu) {
        requireEditableDefinition(gameId);
        catalog.updateGame(gameId, gameName, gameDescription, minimumPlayers,
                maximumPlayers, minimumCpu, maximumCpu);
        return "redirect:/admin/games/" + gameId + "?saved=true";
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

    private void requireEditableDefinition(long gameId) {
        if (!canEditDefinition(definitions.findVersions(gameId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Create or select a draft definition before editing catalogue content");
        }
    }

    private boolean canEditDefinition(List<DefinitionVersion> versions) {
        return versions.stream().noneMatch(version -> "published".equals(version.status()))
                && versions.stream().anyMatch(version -> "draft".equals(version.status()));
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
}
