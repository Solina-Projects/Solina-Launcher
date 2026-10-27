package app.lawnchair.ui.preferences.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.launcher3.R

/**
 * Represents the UI state for the "About" screen.
 *
 * This data class holds all the information displayed on the "About" screen,
 * including application version details, team member lists, relevant links,
 * and the current state of the update checker.
 *
 * @param versionName The current version name of the application.
 * @param commitHash The commit hash of the current build.
 * @param coreTeam A list of [TeamMember] objects representing the core development team.
 * @param supportAndPr A list of [TeamMember] objects representing those involved in support and public relations.
 * @param topLinks A list of [Link] objects representing useful external links (e.g., social media, website).
 */
data class AboutUiState(
    val versionName: String = "",
    val commitHash: String = "",
    val coreTeam: List<TeamMember> = emptyList(),
    val supportAndPr: List<TeamMember> = emptyList(),
    val topLinks: List<Link> = emptyList(),
    val bottomLinks: List<Link> = emptyList(),
)

/**
 * Represents a team member involved in the project.
 *
 * This data class stores information about a team member, including their name,
 * role, photo, social media link, GitHub username, and current contribution status.
 *
 * @param name The name of the team member.
 * @param role The [Role] of the team member within the project.
 * @param photoUrl The URL of the team member's profile photo.
 * @param socialUrl The URL to the team member's primary social media profile or website.
 * @param githubUsername The team member's GitHub username, if available. Defaults to `null`.
 * @param status The current [ContributorStatus] of the team member. Defaults to [ContributorStatus.Idle].
 */
data class TeamMember(
    val name: String,
    val role: Role,
    val photoUrl: String,
    val socialUrl: String,
    val githubUsername: String? = null,
    val status: ContributorStatus = ContributorStatus.Idle,
)

/**
 * Represents the role of a team member within the project.
 *
 * This enum class defines the different roles a contributor can have,
 * each associated with a string resource ID for its description.
 *
 * @param descriptionResId The resource ID of the string describing the role.
 */
enum class Role(val descriptionResId: Int) {
    Development(descriptionResId = R.string.development),
    DevOps(descriptionResId = R.string.devops),
    QuickSwitchMaintenance(descriptionResId = R.string.quickswitch_maintenance),
    Support(descriptionResId = R.string.support),
    SupportAndPr(descriptionResId = R.string.support_and_pr),
}

/**
 * Represents a social or community link.
 *
 * This data class is used to store information about external links,
 * such as links to social media profiles or community forums.
 *
 * @param iconResId The resource ID of the drawable to be used as the icon for the link.
 * @param labelResId The resource ID of the string to be used as the label for the link.
 * @param url The URL string that the link points to.
 */
data class Link(
    @DrawableRes val iconResId: Int,
    @StringRes val labelResId: Int,
    val url: String,
)

/**
 * Represents the status of a contributor.
 *
 * This enum is used to indicate whether a team member is currently active in the project or is currently idle.
 *
 * @property Active Indicates that the contributor is currently active.
 * @property Idle Indicates that the contributor is currently idle.
 */
enum class ContributorStatus {
    Active,
    Idle,
}
