// Übernommen aus: https://git.system.local/projects/SDA/repos/jenkins_shared_libraries/browse/vars/si_psql.groovy?at=193c48536ed875d7184d60ee966739a71d5cdcc4
import groovy.transform.Field

@Field
private static final String PSQL_DOCKER_IMAGE = 'prod.docker.system.local/sda/centos7-psql:1'

/**
 * Drops all schemas that do not have a corresponding git branch.
 */
void dropObsoleteSchemas(
	String serviceGroup,
	String postgresUser,
	String postgresPassword,
	String dbHost,
	int dbPort,
	String dbName,
	String prefix
) {
	def neededSchemas = normalizeSchemaNames(listGitBranches(), prefix)
	def currentSchemas = querySchemaNames(serviceGroup,
		postgresUser,
		postgresPassword,
		dbHost,
		dbPort,
		dbName)
	def obsoleteSchemas = findObsoleteSchemata(neededSchemas, currentSchemas, prefix)
	if (obsoleteSchemas.isEmpty()) {
		println("No obsolete schemas found.")
	} else {
		for (obsoleteSchema in obsoleteSchemas) {
			println("Drop obsolete schema ${obsoleteSchema}.")
			dropSchema(serviceGroup,
				postgresUser,
				postgresPassword,
				dbHost,
				dbPort,
				dbName,
				obsoleteSchema)
		}
	}
}

/**
 * Creates a valid schema name from the given name.
 */
String normalizeSchemaName(String name) {
	// using openshift branch name formatter first
	def normalizedName = si_openshift.filterBranchName(name)

	// postgres specific normalization. (not checking for leading digits:
	// a DNS-1035 label must consist of lower case alphanumeric characters or '-', start with an alphabetic character,
	// and end with an alphanumeric character (e.g. 'my-name',  or 'abc-123', regex used for validation is '[a-z]([-a-z0-9]*[a-z0-9])?'))
	normalizedName = normalizedName.replaceAll('-', '_')  // replace minus with underscore

	return normalizedName
}

private List<String> findObsoleteSchemata(List<String> neededSchemata, List<String> currentSchemata, String prefix) {
	def obsoleteSchemas = new ArrayList()
	for (currentSchema in currentSchemata) {
		if (isObsoltete(currentSchema, neededSchemata, prefix)) {
			obsoleteSchemas.add(currentSchema)
		}
	}
	return obsoleteSchemas
}

private boolean isObsoltete(String currentSchema, List<String> neededSchemata, String prefix) {
	if (!currentSchema.startsWith(prefix)) {
		return false
	}
	for (neededSchema in neededSchemata) {
		if (currentSchema == neededSchema) {
			return false
		}
	}
	return true
}

/**
 * Drops a schema from a database.
 */
private void dropSchema(
	String serviceGroup,
	String postgresUser,
	String postgresPassword,
	String dbHost,
	int dbPort,
	String dbName,
	String schemaName
) {
	si_docker.execContainer(serviceGroup,
		'',
		PSQL_DOCKER_IMAGE,
		"postgresql://${postgresUser}:${postgresPassword}@${dbHost}:${dbPort}/${dbName} -c 'DROP SCHEMA IF EXISTS \"${schemaName}\" CASCADE'")
}

/**
 * Returns the names of all git branches.
 */
private List<String> listGitBranches() {
	def branchList = new ArrayList()
	def branchListString = sh(returnStdout: true,
		script: """
                git remote update origin --prune
                git branch -r
            """).trim()

	if (branchListString.length() > 0) {
		for (branchName in branchListString.readLines()) {
			if (branchName.contains('origin/')) {
				branchList.add(branchName.replaceAll('^ *origin/', ''))
			}
		}
	}

	return branchList
}

/**
 * Creates a list of valid schema names from the given names.
 */
private List<String> normalizeSchemaNames(List<String> names, String prefix) {
	def normalized = new ArrayList()
	for (name in names) {
		normalized.add(normalizeSchemaName(prefix + name))
	}
	return normalized
}

/**
 * Query the names of all database schemas owned by the specified user.
 */
private List<String> querySchemaNames(
	String serviceGroup,
	String postgresUser,
	String postgresPassword,
	String dbHost,
	int dbPort,
	String dbName
) {
	def psqlResponse = si_docker.execContainer(serviceGroup,
		'',
		PSQL_DOCKER_IMAGE,
		"postgresql://${postgresUser}:${postgresPassword}@${dbHost}:${dbPort}/${dbName} -t -A -F , -c \\\\dn")

	def schemaNames = new ArrayList()
	for (line in psqlResponse.lines()) {
		def parts = line.split(",")
		if (parts.length == 2) {
			if (postgresUser == parts[1].trim()) {
				schemaNames.add(parts[0].trim())
			}
		}
	}
	return schemaNames
}
