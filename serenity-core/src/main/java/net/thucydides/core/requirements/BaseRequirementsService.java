package net.thucydides.core.requirements;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import net.thucydides.core.model.*;
import net.thucydides.core.releases.ReleaseManager;
import net.thucydides.core.reports.html.ReportNameProvider;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.core.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.EMPTY_LIST;
import static net.thucydides.core.reports.html.ReportNameProvider.NO_CONTEXT;


public abstract class BaseRequirementsService implements RequirementsService {
    protected List<Requirement> requirements;
    protected List<Release> releases;
    private Map<Requirement, List<Requirement>> requirementAncestors;

    protected final EnvironmentVariables environmentVariables;

    private static final List<Requirement> NO_REQUIREMENTS = Lists.newArrayList();
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseRequirementsService.class);

    public BaseRequirementsService(EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public abstract List<Requirement> getRequirements();

    public abstract  List<? extends RequirementsTagProvider> getRequirementsTagProviders();

    public abstract Optional<ReleaseProvider> getReleaseProvider();

    protected List<Requirement> addParentsTo(List<Requirement> requirements) {
        return addParentsTo(requirements, null);
    }

    protected List<Requirement> addParentsTo(List<Requirement> requirements, String parent) {
        List<Requirement> augmentedRequirements = Lists.newArrayList();
        for(Requirement requirement : requirements) {
            List<Requirement> children = requirement.hasChildren()
                    ? addParentsTo(requirement.getChildren(),requirement.getName()) : NO_REQUIREMENTS;
            augmentedRequirements.add(requirement.withParent(parent).withChildren(children));
        }
        return augmentedRequirements;
    }

    public Optional<Requirement> getParentRequirementFor(TestOutcome testOutcome) {

        try {
            for (RequirementsTagProvider tagProvider : getRequirementsTagProviders()) {
                Optional<Requirement> requirement = getParentRequirementOf(testOutcome, tagProvider);
                if (requirement.isPresent()) {
                    return requirement;
                }
            }
        } catch (RuntimeException handleTagProvidersElegantly) {
            LOGGER.error("Tag provider failure", handleTagProvidersElegantly);
        }
        return Optional.absent();
    }

    public Optional<Requirement> getRequirementFor(TestTag tag) {

        try {
            for (RequirementsTagProvider tagProvider : getRequirementsTagProviders()) {
                Optional<Requirement> requirement = tagProvider.getRequirementFor(tag);
                if (requirement.isPresent()) {
                    return requirement;
                }
            }
        } catch (RuntimeException handleTagProvidersElegantly) {
            LOGGER.error("Tag provider failure", handleTagProvidersElegantly);
        }
        return Optional.absent();
    }

    public List<Requirement> getAncestorRequirementsFor(TestOutcome testOutcome) {
        for (RequirementsTagProvider tagProvider : getRequirementsTagProviders()) {


            Optional<Requirement> requirement = getParentRequirementOf(testOutcome, tagProvider);
            if (requirement.isPresent()) {
                LOGGER.debug("Requirement found for test outcome " + testOutcome.getTitle() + "-" + testOutcome.getIssueKeys() + ": " + requirement);
                if (getRequirementAncestors().containsKey(requirement.get())) {
                    return getRequirementAncestors().get(requirement.get());
                } else {
                    LOGGER.warn("Requirement without identified ancestors found test outcome " + testOutcome.getTitle() + "-" + testOutcome.getIssueKeys() + ": " + requirement);
                }
            }
        }
        return EMPTY_LIST;
    }


    protected void indexRequirements() {
        requirementAncestors = Maps.newHashMap();
        for (Requirement requirement : requirements) {
            List<Requirement> requirementPath = ImmutableList.of(requirement);
            requirementAncestors.put(requirement, ImmutableList.of(requirement));
            indexChildRequirements(requirementPath, requirement.getChildren());
        }
    }


    private void indexChildRequirements(List<Requirement> ancestors, List<Requirement> children) {
        for (Requirement requirement : children) {
            List<Requirement> requirementPath = newArrayList(ancestors);
            requirementPath.add(requirement);
            requirementAncestors.put(requirement, ImmutableList.copyOf(requirementPath));
            indexChildRequirements(requirementPath, requirement.getChildren());
        }
    }

    private ReleaseManager releaseManager;

    private ReleaseManager getReleaseManager() {
        if (releaseManager == null) {
            ReportNameProvider defaultNameProvider = new ReportNameProvider(NO_CONTEXT, ReportType.HTML, this);
            releaseManager = new ReleaseManager(environmentVariables, defaultNameProvider, this);
        }
        return releaseManager;
    }


    private Map<Requirement, List<Requirement>> getRequirementAncestors() {
        if (requirementAncestors == null) {
            getRequirements();
        }
        return requirementAncestors;
    }

    Map<TestOutcome, Optional<Requirement>> requirementCache = Maps.newConcurrentMap();

    private Optional<Requirement> getParentRequirementOf(TestOutcome testOutcome, RequirementsTagProvider tagProvider) {
//        if (requirementCache.containsKey(testOutcome)) {
//            return requirementCache.get(testOutcome);
//        }

        Optional<Requirement> parentDefinedInTags = tagProvider.getParentRequirementOf(testOutcome);
        if (parentDefinedInTags.isPresent()) {
            Optional<Requirement> matchingIndexedParentRequirement = findMatchingIndexedRequirement(parentDefinedInTags.get());
//            requirementCache.put(testOutcome, matchingIndexedParentRequirement);
            return matchingIndexedParentRequirement;
        }

        return Optional.absent();
    }

    private Optional<Requirement> findMatchingIndexedRequirement(Requirement requirement) {
        for(Requirement indexedRequirement : AllRequirements.in(requirements)) {
            if (requirement.matches(indexedRequirement)) {
                return Optional.of(indexedRequirement);
            }
        }
        return Optional.absent();
    }

    public List<Release> getReleasesFromRequirements() {
        if (releases == null) {
            if (getReleaseProvider().isPresent() && (getReleaseProvider().get().isActive())) {
                releases = getReleaseProvider().get().getReleases();
            } else {
                List<List<String>> releaseVersions = getReleaseVersionsFrom(getRequirements());
                releases = getReleaseManager().extractReleasesFrom(releaseVersions);
            }
        }
        return releases;
    }

    public List<String> getTopLevelRequirementTypes() {
        Set<String> requirementTypes = Sets.newHashSet();
        for(Requirement requirement : getRequirements()) {
            requirementTypes.add(requirement.getType());
        }
        return ImmutableList.copyOf(requirementTypes);
    }

    public List<String> getRequirementTypes() {
        Set<String> requirementTypes = Sets.newHashSet();
        requirementTypes.addAll(requirementTypesDefinedIn(getRequirements()));

        return ImmutableList.copyOf(requirementTypes);
    }

    private Collection<? extends String> requirementTypesDefinedIn(List<Requirement> requirements) {
        Set<String> requirementTypes = Sets.newHashSet();
        for(Requirement requirement : requirements) {
            requirementTypes.add(requirement.getType());
            if (!requirement.getChildren().isEmpty()) {
                requirementTypes.addAll(requirementTypesDefinedIn(requirement.getChildren()));
            }
        }
        return requirementTypes;
    }


    @Override
    public List<String> getReleaseVersionsFor(TestOutcome testOutcome) {
        List<String> releases = newArrayList(testOutcome.getVersions());
        for (Requirement parentRequirement : getAncestorRequirementsFor(testOutcome)) {
            releases.addAll(parentRequirement.getReleaseVersions());
        }
        return releases;
    }


    private List<List<String>> getReleaseVersionsFrom(List<Requirement> requirements) {
        List<List<String>> releaseVersions = newArrayList();
        for (Requirement requirement : requirements) {
            releaseVersions.add(requirement.getReleaseVersions());
            releaseVersions.addAll(getReleaseVersionsFrom(requirement.getChildren()));
        }
        return releaseVersions;
    }

    @Override
    public boolean isRequirementsTag(TestTag tag) {
        return getRequirementTypes().contains(tag.getType());
    }

}
