/*
 #-------- SQL Oracle with Groovy -------------#
 - Setup: Put the oracle ojdbc6.jar in the same folder as this script
 - Update path in script here for the jenkins groovy jar
 - Here is the invocation on the command line
 java -cp ".;ojdbc6.jar;C:\Program Files (x86)\Jenkins\war\WEB-INF\lib\groovy-all-2.4.7.jar" groovy.ui.GroovyMain c:\automation\source\groovy\db_connect.groovy
*/
// @ExecutionModes({ON_SINGLE_NODE})
package src.com.dbmaestro
import src.com.dbmaestro.Utils as Utils;

import java.sql.Connection
import groovy.sql.Sql
//import oracle.jdbc.pool.OracleDataSource
import groovy.json.*
import java.io.File
import java.text.SimpleDateFormat

def DbmConnect(){
	ut = new Utils()
}

def execute(init_settings){
  ut = new Utils()
  sep = "\\" //FIXME Reset for windows
  def base_path = new File(getClass().protectionDomain.codeSource.location.path).parent
  def resource_path = "${base_path}${sep}..${sep}..${sep}..${sep}resources"
  def jsonSlurper = new JsonSlurper()
  def json_file = "dbm_queries.json"
  def settings_file = "local_settings_include.json"
  file_contents = [:]
  contents = [:]
  settings = [:]
  ut.separator()
  println "loading..."
  println "JSON Settings Document: ${base_path}${sep}resources${sep}settings${sep}${settings_file}"
  def json_file_obj = new File( "${base_path}${sep}resources${sep}settings", settings_file )
  if (json_file_obj.exists() ) {
    settings = jsonSlurper.parseText(json_file_obj.text)
  }else{
    println "Cannot find settings file"
  }
  init_settings.each {k,v ->
    settings[k] = v
  }
  
  println "JSON Config Document: ${base_path}${sep}resources${sep}${json_file}"
  json_file_obj = new File( "${base_path}${sep}resources", json_file )
  if (json_file_obj.exists() ) {
    file_contents = jsonSlurper.parseText(json_file_obj.text)
  }else{
      println "Cannot find queries file"
  }
  println "... done"

  if (settings["arg_map"].containsKey("action")) {
    switch (settings["arg_map"]["action"].toLowerCase()) {
      case "dbm_package":
        dbm_package
        break
      case "adhoc_package":
        adhocify_package()
        break
      case "disable_package":
        disable_package()
        break
      case "empty_package":
        empty_package()
        break
      case "transfer_packages":
        transfer_packages()
        break
      case "status_message":
        status_message()
        break
      default:
        perform_query()
        break
    }
  }else{
    if (settings["arg_map"].containsKey("help")) {
      ut.message_box("dbm_api HELP", "title")
      file_contents.each { k,v ->
        println "${k}: ${v["name"]}"
        println "\tUsage: ${v["usage"]}"
        println " --------- "
      }
    }else{
      println "Error: specify action=<action> as argument"
      System.exit(1)

    }
  }
}

def status_message(){
	println "Deliver message"
	ut.message_box("Status: ${settings["arg_map"]["ARG1"]}")
}

def perform_query() {
  if (!file_contents.containsKey(settings["arg_map"]["action"])) {
    println "Error: Action: ${settings["arg_map"]["action"]} - not found!"
    println "Available: ${file_contents.keySet()}"
    System.exit(1)
  }
  contents = file_contents[settings["arg_map"]["action"]]
  ut.message_box("Task: ${settings["arg_map"]["action"]}")
  println " Description: ${contents["name"]}"
  for (query in contents["queries"]) {
    def post_results = ""
    ut.separator()
    def conn = sql_connection(query["connection"].toLowerCase())
    //println "Raw Query: ${query["query"]}"
    def query_stg = add_query_arguments(query["query"])
    println "Processed Query: ${query_stg}"
    ut.message_box("Results")
    def header = ""
    query["output"].each{arr ->
      header += "| ${arr[0].padRight(arr[2])}"
      }
    println header
    ut.separator(100)
    conn.eachRow(query_stg)
    { row ->
      query["output"].each{arr ->
        def val = row.getAt(arr[1])
        print "| ${val.toString().trim().padRight(arr[2])}"
      }
      println " "
    }
    ut.separator(100)
    println ""
    if (query.containsKey("post_process")) {
      post_process(query["post_process"], query_stg, conn)
    }
    conn.close()

  }
}


def post_process(option, query_string, connection){
  //println "Option: ${option}"
  def result = ""
  //println "Running post-processing: ${option}"
  switch (option.toLowerCase()) {
    case "export_packages":
      export_packages(query_string, connection)
      break
    case "create_control_json":
      //create_control_json(query_string, connection)
      break
    case "show_object_ddl":
      show_object_ddl(query_string, connection)
      break
  }
  return result
}

def result_query(query, grab = []){
  ut.message_box("Results")
  def header = ""
  def result = [:]
  def conn = sql_connection("repo")
	grab.each {item ->
		result[item] = []
	}

  query["output"].each{arr ->
    header += "| ${arr[0].padRight(arr[2])}"
  }
  println header
  ut.separator(100)
  conn.eachRow(query["query"])
  { row ->
    query["output"].each{arr ->
      def val = row.getAt(arr[1])
      print "| ${val.toString().trim().padRight(arr[2])}"
    }
	grab.each {item ->
		result[item].add(row.getAt(item))
	}
    println " "
  }
  ut.separator(100)
  println ""
  conn.close()
  return result
}

def sql_connection(conn_type) {
  def user = ""
  def password = ""
  def conn = ""
  if (conn_type == "repo" || conn_type == "repository") {
    user = settings["connections"]["repository"]["user"]
    if (settings["connections"]["repository"].containsKey("password_enc")) {
      password = password_decrypt(settings["connections"]["repository"]["password_enc"])
    }else{
      password = settings["connections"]["repository"]["password"]
    }
    conn = settings["connections"]["repository"]["connect"]
  }else if (conn_type == "remote") {
    // FIXME find instance for named environment and build it
    user = settings["connections"]["remote"]["user"]
    if (settings["remote"].containsKey("password_enc")) {
      password = password_decrypt(settings["connections"]["remote"]["password_enc"])
    }else{
      password = settings["connections"]["remote"]["password"]
    }
    conn = settings["connections"]["remote"]["connect"]
  }
  // Assign local settings
  println "Querying ${conn_type} Db: ${conn}"
  return Sql.newInstance("jdbc:oracle:thin:@${conn}", user, password)
}

def export_packages(query_string, conn){
  def jsonSlurper = new JsonSlurper()
  def date = new Date()
  def seed_list = [:]
  def contents = [:]
  sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
  def src = ""
  def cur_ver = ""
  def p_list = ""
  def source_pipeline = ""
  def target_pipeline = System.getenv("TARGET_PIPELINE").trim()
  if(! target_pipeline){
    println "Error: Set TARGET_PIPELINE environment variable or pass path to control.json as 2nd argument"
    System.exit(1)
  }
  if ( System.getenv("EXPORT_PACKAGES") == null ) {
    println "Error: Set EXPORT_PACKAGES environment variable to specify which packages move forward"
    System.exit(1)
  }else{ 
    p_list = System.getenv("EXPORT_PACKAGES") 
  }
  def consolidate_version = System.getenv("CONSOLIDATE_VERSION").trim()
  if(consolidate_version != "none"){
    println "Consolidating packages to version: ${consolidate_version}"
  }
  def target_schema = get_target_schema(target_pipeline)

  ut.message_box("Exporting Versions")
  if( p_list && p_list != "" ){
    p_list.split(",").each{
      if (it.contains("=>")) {
        def parts = it.split("=>")
        seed_list[parts[0].trim()] = parts[1].trim()
      }else{
        seed_list[it.trim()] = ""
      }
    }
    //println seed_list
  }
  println "Target Pipeline: ${target_pipeline}, schema: ${target_schema}"
  println "Packages: ${p_list}"
  def result = ""
  def tmp_path = "${settings["general"]["staging_path"]}${sep}${target_pipeline}${sep}${target_schema}"
  def target_path = tmp_path
  def fil_name = ""
  def hdr = ""
  def do_save = false
  def counter = 0
  // Redo query and loop through records
  hdr += "-- Exported from pipeline: ${target_pipeline} on ${sdf.format(date)}\n"
  conn.eachRow(query_string)
  { rec ->
    cur_ver = "${rec.version}".toString()
	  target_ver = cur_ver
    result = cur_ver
    do_save = false
    hdr += "-- Version - ${cur_ver}, created: ${rec.created_at}\n"
    if (seed_list.containsKey(cur_ver) && rec.script.endsWith(".sql") ) {
      if(consolidate_version != "none"){
        target_ver = consolidate_version
      }
      if(seed_list[cur_ver] != ""){
        target_ver = seed_list[cur_ver]
      }
      src = new File(rec.script_sorce_data_reference).text
      tmp_path = "${target_path}${sep}${target_ver}"
      ut.ensure_dir(tmp_path)
      fil_name = "${sortable(counter)}_${rec.script}"
      src = hdr + src
      //println src
      println "Exporting Script: ${rec.script}, Target: ${target_path}"
      ut.create_file(tmp_path, fil_name, src)
      result += " - Transfer Version (${target_ver})"
    }else{
      result += " - Skip Version"
    }
    counter += 1
    println result
  }
}

def dbm_package() {
  def java_cmd = settings["general"]["java_cmd"]
  def server = settings["general"]["server"]
  def target_pipeline = System.getenv("TARGET_PIPELINE")
  def base_path = settings["general"]["staging_path"]
  def base_schema = get_target_schema(target_pipeline)
  println "#-------- Performing DBmPackage command ----------#"
  println "# Cmd: ${java_cmd} -Package -ProjectName ${target_pipeline} -Server ${server}"
  def results = "${java_cmd} -Package -ProjectName ${target_pipeline} -Server ${server} ".execute().text
}

def adhocify_package() {
  def package_name = settings["arg_map"]["ARG1"]
  ut.separator()
  def parts = package_name.split("__")
  def new_name = parts.length == 2 ? parts[1] : package_name
  def query = "update twmanagedb.TBL_SMG_VERSION set NAME = 'ARG_NAME', UNIQ_NAME = 'ARG_NAME', TYPE_ID = 2 where NAME = 'ARG_FULL_NAME'"
  def conn = sql_connection("repository")
  //println "Raw Query: ${query["query"]}"
  def query_stg = query.replaceAll("ARG_FULL_NAME", package_name)
  query_stg = query_stg.replaceAll("ARG_NAME", new_name)
  println "Processed Query: ${query_stg}"
  ut.message_box("Results")
  def res = conn.execute(query_stg)
  println res
  ut.separator()
  conn.close()
}

def disable_package() {
  def package_name = settings["arg_map"]["ARG1"]
  ut.separator()
  def query = "update twmanagedb.TBL_SMG_VERSION set IS_ENABLED = 0 where NAME = 'ARG_FULL_NAME'"
  def conn = sql_connection("repository")
  //println "Raw Query: ${query["query"]}"
  def query_stg = query.replaceAll("ARG_FULL_NAME", package_name)
  println "Processed Query: ${query_stg}"
  ut.message_box("Results")
  def res = conn.execute(query_stg)
  println res
  ut.separator()
  conn.close()
}

def show_object_ddl(query_string, conn) {
  // Redo query and loop through records
  conn.eachRow(query_string)
  { rec ->
    ut.message_box("Object DDL Rev: ${rec.COUNTEDREVISION} of ${rec.OBJECT_NAME}")
    java.sql.Clob clob = (java.sql.Clob) rec.OBJECTCREATIONSCRIPT
    bodyText = clob.getAsciiStream().getText()
    println bodyText
  }
}

// #--------- UTILITY ROUTINES ------------#

def get_export_json_file(target, path_only = false){
  def jsonSlurper = new JsonSlurper()
  def contents = [:]
  def export_path_temp = "${settings["general"]["staging_path"]}${sep}${target}${sep}export_control.json"
  println "JSON Export config: ${export_path_temp}"
  if(path_only){
    return export_path_temp
  }
  def json_file_obj = new File( export_path_temp )
  if (json_file_obj.exists() ) {
    contents = jsonSlurper.parseText(json_file_obj.text)
  }
  return contents
}

def get_target_schema(cur_pipeline){
  def target_schema = ""
  settings["branch_map"].each { k,v ->
    def cur_branch = k
    v.each { pipe -> if (pipe["pipeline"] == cur_pipeline){ target_schema = pipe["base_schema"] } }
  }
  return target_schema
}

def add_query_arguments(query){
  def result_stg = query
  if (query.contains("ARG1")){
    if (settings["arg_map"].containsKey("ARG1")){
      (0..10).each {
        def cur_key = "ARG${it}".toString()
        if(settings["arg_map"].containsKey(cur_key)){
          //println "Find: ${cur_key} => ${settings["arg_map"][cur_key]}"
          result_stg = result_stg.replaceAll(cur_key, settings["arg_map"][cur_key])
        }else{
          //println "Find: ${cur_key} => %"
          result_stg = result_stg.replaceAll(cur_key, '%')
        }
      }
    }else{
        println "ERROR - query requires ARG values"
        System.exit(1)
    }
  }
  return result_stg
}

def sql_file_list(dir_txt) {
  // Returns files in ascending date order
  def files=[]
  def src = new File(dir_txt)
  src.eachFile groovy.io.FileType.FILES, { file ->
    if (file.name.contains(".sql")) {
      files << file
    }
  }
  return files.sort{ a,b -> a.lastModified() <=> b.lastModified() }
}

def path_from_pipeline(pipe_name){
  def query_stg = "select f.FLOWID, f.FLOWNAME, s.SCRIPTOUTPUTFOLDER from TWMANAGEDB.TBL_FLOW f INNER JOIN TWMANAGEDB.TBL_FLOW_SETTINGS s ON f.FLOWID = s.FLOWID WHERE f.FLOWNAME = 'ARG1'"
  def result = ""
  sql.eachRow(query_stg.replaceAll("ARG1", pipe_name))
  { row ->
    result = row.SCRIPTOUTPUTFOLDER
  }
  return result
}


def empty_package(){
  def contents = file_contents["package_content"]
  def version = settings["arg_map"]["ARG2"]
  def pipeline = settings["arg_map"]["ARG1"]
  def cnt = 0
  ut.message_box("Task: Empty Package - ")
  println " Description: ${contents["name"]}\nARGS: ${settings["arg_map"]}"
  def query = contents["queries"][0]
  ver_query = query["query"]
  ver_query = ver_query.replaceAll('ARG1', pipeline)
  ver_query = ver_query.replaceAll('ARG2', version)
  query["query"] = ver_query
  def results = result_query(query, ["SCRIPT_ID","script","SCRIPT_SORCE_DATA_REFERENCE"])
  println " Processed Query: ${query["query"]}"
  def conn = sql_connection("repo")
  results["SCRIPT_ID"].each {script_id -> 
    println "Removing script_id = ${script_id}"
    conn.call("{call PKG_RM.DELETE_SCRIPT(?,?)}", [script_id, Sql.VARCHAR]) { was_deleted ->
		if (was_deleted == 'TRUE') {println "Deleted Successfully (${was_deleted})"}
	}
    println "Remove from file system: ${results["SCRIPT_SORCE_DATA_REFERENCE"][cnt]}"
	def fil = new File(results["SCRIPT_SORCE_DATA_REFERENCE"][cnt])
	fil.delete()
	cnt += 1
  }
  conn.close()
}

def changeStagingDir() {
  // Change the product staging directory
  if (!settings["arg_map"].containsKey("pipeline")) {
    println "Send pipeline= and path= arguments"
    System.exit(1)
  }
  def flowid = 0
  def old_path = ""
  def pipeline = settings["arg_map"]["pipeline"]
  def query_stg = "select s.flowid, s.SCRIPTOUTPUTFOLDER from TWMANAGEDB.TBL_FLOW_SETTINGS s INNER JOIN TBL_FLOW f on f.FLOWID = s.FLOWID WHERE FLOWNAME = '${pipeline}'"
  println ut.message_box("Change Staging Folder", "title")
  def new_path = settings["arg_map"]["path"]
  println "Pipeline: ${pipeline}"
  def conn = sql_connection("repo")
  conn.eachRow(query_stg) { rec ->
    old_path = rec["SCRIPTOUTPUTFOLDER"]
    println "Existing: ${old_path}"
    flowid = rec["FLOWID"]
  }
  ut.ensure_dir()
  println "New: ${new_path}"
  println ""
  println "=> Update Flow Record"
  query_stg = "update TWMANAGEDB.TBL_FLOW_SETTINGS set SCRIPTOUTPUTFOLDER = '${new_path}' where FLOWID = ${flowid}"
  conn.execute(query_stg)
  println "=> Update Script Import Records"
  def query = "update TWMANAGEDB.TBL_SMG_MANAGED_DYNAMIC_SCR set SCRIPT_SORCE_DATA_REFERENCE = REPLACE(SCRIPT_SORCE_DATA_REFERENCE, '${old_path}', '${new_path}') where SCRIPT_ID IN (SELECT SCRIPT_ID from TWMANAGEDB.TBL_SMG_MANAGED_STATIC_SCR s INNER JOIN TWMANAGEDB.TBL_VERSION v ON v.ID = s.VERSION_ID WHERE v.FLOW_ID = '${flowid}' )"
  conn.execute(query_stg)
  println "=> Update Script Import Records"
  query_stg = "update TWMANAGEDB.TBL_SMG_BRANCH set DATA_SOURCE_PATH = REPLACE(DATA_SOURCE_PATH, '${old_path}', '${new_path}') where SCRIPT_ID IN (SELECT SCRIPT_ID from TWMANAGEDB.TBL_SMG_MANAGED_STATIC_SCR s INNER JOIN TWMANAGEDB.TBL_VERSION v ON v.ID = s.VERSION_ID WHERE v.FLOW_ID = '${flowid}' )"
  conn.execute(query_stg)
}

def getNextVersion(optionType){
  //Get version from currentVersion.txt file D:\\repo\\proj
  // looks like this:
  // develop=1.10.01
  // release=1.9.03
  def newVersion = ""
  def curVersion = [:]
  def versionFile = "D:\\projddu\\proj\\currentVersion.txt"
  def fil = new File(versionFile)
  def contents = fil.readLines
  contents.each{ -> cur
    def pair = cur.split("=")
    curVersion[pair[0].trim()] = pair[1].trim()
  }
  
  switch (optionType.toLowerCase()) {
    case "develop":
      curVersion["develop"] = newVersion = incrementVersion(curVersion["develop"])
      break
    case "hotfix":
      curVersion["develop"] = newVersion = incrementVersion(curVersion["develop"], "other")
      break
    case "cross_over":
      curVersion["release"] = newVersion = incrementVersion(curVersion["release"])
      break
    case "ddu":
      curVersion["release"] = newVersion = incrementVersion(curVersion["release"], "other")
      break
  }
  stg = "develop=${curVersion["develop"]}\r\n"
  stg += "release=${curVersion["release"]}"
  fil.write(stg)
  fil.close()
  return newVersion
}

def incrementVersion(ver, process = "normal"){
  // ver = 1.9.04
  def new_ver = ver
  def parts = ver.split('\\.')
  println parts
  if(process == "normal"){
      parts[2] = (parts[2].toInteger() + 1).toString()
      new_ver = parts[0..2].join(".")
      println new_ver
  }else{
      if(parts.size() > 3){
          parts[3] = (parts[3].toInteger() + 1).toString()
      }else{
          parts = parts + '1'
      }
      println parts[3]
      new_ver = parts[0..3].join(".")
      println new_ver
  }
}

def sortable(inum){
  ans = "00"
  def icnt = inum.toInteger()
  //incoming int
  def seq = ['A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','R','S','T','U','V','W','X','Y','Z','0','1','2','3','4','5','6','7','8','9']
  def iter = (icnt/36).toInteger()
  def remain = icnt % 36
  return "${seq.get(iter)}${seq.get(remain)}"
}

def transfer_packages(){
  settings["arg_map"]["action"] = "package_export"
  perform_query()
  
}
