package org.dbmaestro;

def upgrade_environment(pipe, env_num){
  def environment = pipe["environments"][env_num]
  def approver = pipe["approvers"][env_num]
  def pair = environment.split(",")
  def do_pair = false
  def version = pipe["version"]
  if(!version.startsWith("V")) { version = "V${version}" }
  if (pair.size() > 1) {environment = pair[0] }
  do_pair = (pair.size() > 1) 
  if(approver != "none"){
	  input message: "Deploy to ${environment}?", submitter: approver
  }
	echo "#------------------- Performing Deploy on ${environment} --------------#"
	for(env in pair){
		bat "${pipe["java_cmd"]} -Upgrade -ProjectName ${pipe["pipeline"]} -EnvName ${env} -PackageName ${version} -Server ${pipe["server"]} ${pipe["credential"]}"
  }
}

def package_artifacts(pipe, env_num){
  def version = pipe["version"]
  def v_version = version
  if( !version.startsWith("V") ) {v_version = "V#{version}" } 
  def staging_dir = "${pipe["staging_dir"]}${sep()}${pipe["pipeline"]}${sep()}${pipe["base_schema"]}"
  def source_dir = pipe["source_dir"]
  if(!env.Skip_Packaging || env.Skip_Packaging == "No"){
    echo "#------------------- Copying files for ${version} ---------#"
    bat "if exist ${staging_dir} del /q ${staging_dir}\\*"
		echo "# Cleaning Directory"
		bat "del /q \"${staging_dir}\\*\""
		bat "FOR /D %%p IN (\"${staging_dir}\\*.*\") DO rmdir \"%%p\" /s /q"
		// This is for when files are prefixed with <dbcr_result>
    //bat "if not exist \"${staging_dir}${sep()}${version}\" mkdir \"${staging_dir}${sep()}${version}\""
    //bat "copy \"${source_dir}${sep()}${dbcr_result}*.sql\" \"${staging_dir}${sep()}${version}\""
    // This is for copying a whole directory
    bat "xcopy /s /y /i \"${source_dir}${sep()}${version}\" \"${staging_dir}${sep()}${v_version}\""
    // trigger packaging
    echo "#----------------- Packaging Files for ${version} -------#"
    bat "${pipe["java_cmd"]} -Package -ProjectName ${pipe["pipeline"]} -Server ${pipe["server"]} ${pipe["credential"]}"
    // version = adhoc_package(version)
  }else{
	  echo "#-------------- Skipping packaging step (parameter set) ---------#"
  }
}

def execute(pipe_info, env_num){
  stage(pipe_info["environments"][env_num]){
    node(pipe_info["dbm_node"]){
	  if(env_num == 0){
		  this.package_artifacts(pipe_info, env_num)
	  }
	  this.upgrade_environment(pipe_info, env_num)
    }
  }
}

return this;
