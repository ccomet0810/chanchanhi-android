package com.example.aplassistmvp0.guide

import java.util.UUID

/**
 * Android-native owner for the typed-native transaction protocol. No Python, I/O or model calls.
 * This object is THE ledger in its runtime; captures/receipts are projections, never UI-owned goals.
 * Interpretation/source hypotheses and declared constraints do not prove language or app truth.
 * Product composition, semantic authority and observation producers are separate integration work.
 */
internal class NativeGoalOwner(schemaJson:String, rulesJson:String="[]", private val session:String="session-1") {
    companion object {
        const val FAMILY="typed-native-v1"
        private val ORDER=listOf("task_order")
        fun pathKey(path:List<String>):String {
            require(path.all(::goalLabel) && (path==ORDER || path.size==2 && path[0] in setOf("stage","target","task","constraint") || path.size==3 && path[0]=="field")){"unknown_native_path"}
            return GoalCanonicalJson.write(path)
        }
        private fun structural(path:List<String>)=path==ORDER || path[0]=="stage"
    }
    data class Input(val ownerId:String,val requestId:String,val turnJson:String,
        val utterance:String,val contextJson:String,val baseDigest:String,val catalogueDigest:String)
    data class Job(val ownerId:String,val id:String,val inputId:String)
    data class Snapshot(val ledgerJson:String,val epoch:Long,val pending:Input?,val catalogueDigest:String,val ownerId:String)
    /** A local declared-goal projection, not semantic or live-screen permission. */
    data class View(val snapshot:Snapshot,val reference:GuideGoalRef?,val report:NativeGoalValidation.Report?)
    data class InterpretationCapture(val input:Input,val job:Job,val view:View,val schemaJson:String,
        val rulesJson:String,val clausesJson:String)
    data class Outcome(val kind:String,val reason:String,val snapshot:Snapshot,val changed:List<String> = emptyList())
    data class Terminal(val id:String,val original:GuideGoalRef,val epoch:Long,
        val revision:Long,val digest:String,val evidenceDigest:String)
    private data class Clause(val id:String,val key:String,val present:Boolean,val generation:Long,val origin:String,
        val dependencies:Map<List<String>,Long>,val parents:List<String>,val predecessor:String?,
        val sourceId:String,val sourceDigest:String,val contextDigest:String,val catalogueDigest:String,
        val supportJson:String?)
    private data class Change(val path:List<String>,val operation:String,val value:String?)
    private val lock=Any()
    val ownerId=UUID.randomUUID().toString()
    private val validator=NativeGoalValidation(schemaJson)
    private val rules=linkedMapOf<String,Map<String,Any?>>()
    private val policy:Map<String,Any?>
    private var catalogue:String
    private var root=1L;private var revision=1L;private var epoch=0L;private var turnSequence=0L;private var settled=0L
    private var family="unassigned";private var active=false;private var turn:Map<String,Any?>?=null
    private var pending:Input?=null;private var ready=false
    private var cells=linkedMapOf<List<String>,String>();private var generations=linkedMapOf<List<String>,Long>()
    private var clauses=linkedMapOf<List<String>,Clause>()
    private val clauseHistory=linkedMapOf<String,Clause>()
    private val sources=linkedMapOf<String,Input>();private val roots=mutableListOf<String>()
    private val jobs=linkedMapOf<String,Pair<Job,Input>>()
    private var registry=linkedMapOf<String,List<String>>()
    private var terminalAuthority:Any?=null;private var terminal:Terminal?=null
    private val terminals=mutableListOf<Terminal>();private val learned=mutableSetOf<String>()
    init {
        require(goalLabel(session))
        for(value in GoalCanonicalJson.parse(rulesJson).goalList()){
            val rule=value.goalObject().exact("rule_id","family","output_field","output","premises")
            val id=rule["rule_id"].goalText();require(goalLabel(id) && id !in rules && rule["family"] in validator.families)
            val definitions=validator.families.getValue(rule["family"].goalText()).goalObject().getValue("fields").goalObject()
            val output=rule["output_field"].goalText();require(output in definitions)
            rule["output"].goalObject().exact("status","value")
            val premises=rule["premises"].goalList().map{it.goalList()};require(premises.isNotEmpty())
            require(premises.all{it.size==2 && it[0] in definitions && it[0]!=output && it[1] is Map<*,*>})
            require(premises.map{it[0]}.toSet().size==premises.size);rules[id]=rule
        }
        policy=mapOf("version" to "native-root-v1","typed_schema" to validator.schema,"rules" to rules.toMap())
        val base=GoalCanonicalJson.digest(mapOf("schema" to mapOf("__unassigned__" to mapOf("allowed" to emptyList<String>(),"allow_any" to false)),
            "references" to emptyMap<String,Any?>(),"conflicts" to emptyList<String>(),"execution_app" to null))
        val evidence=GoalCanonicalJson.digest(mapOf("base" to base,"field_tasks" to mapOf("__unassigned__" to listOf("__unassigned__")),"rules" to emptyMap<String,Any?>()))
        val registered=GoalCanonicalJson.digest(mapOf("base" to evidence,"scope_policy" to "registered-scope-v2"))
        catalogue=GoalCanonicalJson.digest(mapOf("legacy" to registered,"native_policy" to policy))
    }
    private fun state():Map<String,Any?> =mapOf("session" to session,"root" to root,"revision" to revision,"family" to family,
        "active" to active,"turn" to turn,"settled_turn" to settled,
        "slots" to cells.entries.associate{pathKey(it.key) to mapOf("status" to "known","value" to it.value,"package" to null)},
        "generations" to generations.entries.associate{pathKey(it.key) to it.value})
    private fun stateDigest()=GoalCanonicalJson.digest(state())
    private fun currentRef():GuideGoalRef?=if(active && pending==null && ready)GuideGoalRef(ownerId,epoch,root,revision,stateDigest()) else null
    fun reference():GuideGoalRef?=synchronized(lock){currentRef()}
    fun snapshot():Snapshot=synchronized(lock){Snapshot(GoalCanonicalJson.write(state()),epoch,pending,catalogue,ownerId)}
    fun view():View=synchronized(lock){View(snapshot(),currentRef(),if(family==FAMILY)validator.validate(cells) else null)}
    fun consumeSnapshot(expected:Snapshot,effect:()->Unit):Boolean=synchronized(lock){
        if(expected.ownerId!=ownerId || expected!=snapshot())false else{effect();true}
    }
    fun rootHistory():List<String> = synchronized(lock){roots.toList()}
    fun terminalHistory():List<Terminal> = synchronized(lock){terminals.toList()}
    fun clauseView():String=synchronized(lock){GoalCanonicalJson.write(clauses.entries.associate{(path,c)->pathKey(path) to mapOf(
        "id" to c.id,"present" to c.present,"generation" to c.generation,"origin" to c.origin,
        "dependencies" to c.dependencies.entries.associate{pathKey(it.key) to it.value},"parents" to c.parents,"predecessor" to c.predecessor,
        "source_id" to c.sourceId,"source_digest" to c.sourceDigest,"context_digest" to c.contextDigest,
        "catalogue_digest" to c.catalogueDigest,"support_json" to c.supportJson)})}
    fun historyView():String=synchronized(lock){GoalCanonicalJson.write(clauseHistory.mapValues{(_,c)->mapOf(
        "key" to c.key,"generation" to c.generation,"origin" to c.origin,"source_id" to c.sourceId)})}
    private fun outcome(kind:String,reason:String,changed:List<String> = emptyList())=Outcome(kind,reason,snapshot(),changed)
    fun registerUserTurn(text:String, context:Map<String,String> = emptyMap()):Input=synchronized(lock){
        require(goalLabel(text) && GoalCanonicalJson.validText(text)){"empty_or_invalid_user_input"}
        val contextJson=GoalCanonicalJson.write(context);turnSequence=Math.addExact(turnSequence,1)
        turn=mapOf("session" to session,"root" to root,"base_revision" to revision,"turn_id" to turnSequence,"origin" to "user")
        epoch=Math.addExact(epoch,1);ready=false
        Input(ownerId,UUID.randomUUID().toString(),GoalCanonicalJson.write(turn),text,contextJson,stateDigest(),catalogue)
            .also{pending=it;jobs.clear()}
    }
    fun prepare(input:Input):Job=synchronized(lock){
        require(input.ownerId==ownerId && input==pending){"current_original_native_input_required"}
        jobs.clear();Job(ownerId,UUID.randomUUID().toString(),input.requestId).also{jobs[it.id]=it to input}
    }
    fun captured(job:Job):Snapshot?=synchronized(lock){if(jobs[job.id]?.first==job)snapshot() else null}
    /** Local dispatch capture only. A transport must build an explicit privacy-safe projection. */
    fun interpretationCapture(job:Job):InterpretationCapture?=synchronized(lock){
        jobs[job.id]?.takeIf{it.first==job && it.second==pending}?.let{
            InterpretationCapture(it.second,job,view(),validator.canonicalSchema,
                GoalCanonicalJson.write(rules.values.toList()),clauseView())
        }
    }
    fun retire(job:Job):Boolean=synchronized(lock){if(jobs[job.id]?.first==job){jobs.remove(job.id);true}else false}
    private fun spanValid(text:String,value:Any?):Boolean=try {
        val span=value.goalObject().exact("start","end","quote");val start=span["start"].goalLong();val end=span["end"].goalLong()
        val length=text.codePointCount(0,text.length)
        start>=0 && start<end && end<=length && goalLabel(span["quote"].goalText()) &&
            text.substring(text.offsetByCodePoints(0,start.toInt()),text.offsetByCodePoints(0,end.toInt()))==span["quote"]
    }catch(_:Exception){false}
    private fun draftCells(raw:Any?):LinkedHashMap<List<String>,String>{
        val draft=raw.goalObject().exact("stage_ids","targets","tasks","constraints");val result=linkedMapOf<List<String>,String>()
        fun add(path:List<String>,value:Any?){pathKey(path);require(path !in result){"duplicate_native_cell"};result[path]=GoalCanonicalJson.write(value)}
        val tasks=draft.getValue("tasks").goalList().map{it.goalObject()};add(ORDER,mapOf("task_ids" to tasks.map{it["task_id"].goalText()}))
        for(stage in draft.getValue("stage_ids").goalList())add(listOf("stage",stage.goalText()),mapOf("stage_id" to stage))
        for(value in draft.getValue("targets").goalList()){val target=value.goalObject();add(listOf("target",target["target_id"].goalText()),target)}
        for(task in tasks){task.exact("task_id","family","target_id","stage_id","coverage","fields");val id=task["task_id"].goalText()
            add(listOf("task",id),task.filterKeys{it in setOf("family","target_id","stage_id","coverage")})
            for(pair in task.getValue("fields").goalList().map{it.goalList()}){require(pair.size==2);add(listOf("field",id,pair[0].goalText()),pair[1])}}
        for(value in draft.getValue("constraints").goalList()){val c=value.goalObject();add(listOf("constraint",c["constraint_id"].goalText()),c)}
        return result
    }
    private fun taskScope(path:List<String>, values:Map<List<String>,String>):Set<String>{
        val headers=values.filterKeys{it[0]=="task"}.entries.associate{it.key[1] to GoalCanonicalJson.parse(it.value).goalObject()};val all=headers.keys
        return when(path[0]){
            "task","field"->setOf(path[1])
            "target"->headers.filterValues{it["target_id"]==path[1]}.keys.ifEmpty{all}
            "stage"->headers.filterValues{it["stage_id"]==path[1]}.keys.ifEmpty{all}
            "constraint"->{val target=GoalCanonicalJson.parse(values.getValue(path)).goalObject()["target_id"];headers.filterValues{it["target_id"]==target}.keys.ifEmpty{all}}
            else->all
        }
    }
    fun receive(job:Job,packetJson:String):Outcome=synchronized(lock){
        val record=jobs[job.id]
        if(record?.first!=job)return@synchronized outcome("REJECT","unknown_or_retired_native_ticket")
        jobs.remove(job.id);val input=record.second
        if(input!=pending || input.baseDigest!=stateDigest() || input.catalogueDigest!=catalogue)return@synchronized outcome("REJECT","native_input_no_longer_current")
        try {
            val packet=GoalCanonicalJson.parse(packetJson).goalObject().exact("turn","mode","draft","changes","supports","coverage","unresolved_spans","control_spans")
            val mode=packet["mode"].goalText();require(mode in setOf("replace","amend","close") && GoalCanonicalJson.write(packet["turn"])==input.turnJson){"native_packet_mode_or_original_turn"}
            val unresolved=packet.getValue("unresolved_spans").goalList();val control=packet.getValue("control_spans").goalList()
            require((unresolved+control).all{spanValid(input.utterance,it)}){"native_control_source_invalid"}
            require(packet["coverage"]=="closed" && unresolved.isEmpty()){"native_interpretation_coverage_unresolved"}
            require(if(mode in setOf("replace","close"))control.isNotEmpty() else control.isEmpty()){"native_mode_control_source_required"}
            val changes=packet.getValue("changes").goalList();val supportValues=packet.getValue("supports").goalList()
            if(mode=="close"){
                require(packet["draft"]==null && changes.isEmpty() && supportValues.isEmpty()){"native_close_with_mutations"}
                val wasActive=active;active=false;if(wasActive)revision=Math.addExact(revision,1);settled=turnSequence
                epoch=Math.addExact(epoch,1);pending=null;ready=false;sources[input.requestId]=input;jobs.clear()
                return@synchronized outcome(if(wasActive)"CLOSE" else "KEEP","native_guidance_only_closed")
            }
            val replace=mode=="replace";val old=if(replace)linkedMapOf() else LinkedHashMap(cells)
            val candidate:LinkedHashMap<List<String>,String>;val operations=linkedMapOf<List<String>,Change>()
            if(replace){require(changes.isEmpty()){"complete_new_draft_required"};candidate=draftCells(packet["draft"])
                for((path,value)in candidate)if(!structural(path))operations[path]=Change(path,"put",value)
            }else{
                require(family==FAMILY && active && packet["draft"]==null){"active_native_goal_required_for_amendment"};candidate=LinkedHashMap(cells)
                val introduced=mutableListOf<String>()
                for(value in changes){val change=value.goalObject().exact("path","op","value_json");val path=change["path"].goalList().map{it.goalText()};pathKey(path)
                    val op=change["op"].goalText();require(!structural(path) && path !in operations && op in setOf("put","remove")){"invalid_or_duplicate_native_change"}
                    val raw=change["value_json"] as? String
                    if(op=="put"){
                        require(raw!=null && GoalCanonicalJson.write(GoalCanonicalJson.parse(raw))==raw){"canonical_native_value_required"}
                        if(path[0]=="task" && path !in old)introduced.add(path[1]);candidate[path]=raw
                    }else{require(change["value_json"]==null && path in old){"native_remove_requires_present_cell"};candidate.remove(path)}
                    operations[path]=Change(path,op,raw)
                }
                val tasks=candidate.keys.filter{it[0]=="task"}.map{it[1]}.toSet()
                val order=GoalCanonicalJson.parse(old.getValue(ORDER)).goalObject().getValue("task_ids").goalList().map{it.goalText()}.filter{it in tasks}.toMutableList()
                for(id in introduced)if(id in tasks && id !in order)order.add(id)
                require(order.toSet()==tasks){"new_task_introduction_order_missing"};candidate[ORDER]=GoalCanonicalJson.write(mapOf("task_ids" to order))
                for((path,raw)in candidate.toMap()){
                    val value=GoalCanonicalJson.parse(raw).goalObject()
                    val stage=if(path[0]=="task" || path[0]=="constraint" && value["scope"]=="stage")value["stage_id"].goalText() else null
                    if(stage!=null)candidate.putIfAbsent(listOf("stage",stage),GoalCanonicalJson.write(mapOf("stage_id" to stage)))
                }
            }
            val report=validator.validate(candidate);require(report.status !in setOf("INVALID","CONTRADICTION")){"native_candidate_${report.status.lowercase()}"}
            val changed=(old.keys+candidate.keys).filter{old[it]!=candidate[it]}.toSet()
            val supports=linkedMapOf<List<String>,Map<String,Any?>>()
            for(value in supportValues){val support=value.goalObject().exact("path","basis","task_ids","spans","selection","producer","rule_id","premises","old_clause_id")
                val path=support["path"].goalList().map{it.goalText()};pathKey(path);require(path !in supports && path in operations){"native_support_path_or_tasks"}
                val taskIds=support["task_ids"].goalList().map{it.goalText()};require(taskIds.isNotEmpty() && taskIds.all(::goalLabel) && taskIds.toSet().size==taskIds.size){"native_support_path_or_tasks"}
                require(support.getValue("spans").goalList().all{spanValid(input.utterance,it)}){"native_support_source_span"}
                val expected=(if(path in old)taskScope(path,old) else emptySet())+(if(path in candidate)taskScope(path,candidate) else emptySet())
                val selection=support["selection"].goalText();require(taskIds.toSet()==expected && selection in setOf("resolved_one","resolved_all") && (selection!="resolved_one" || taskIds.size==1) && goalLabel(support["producer"].goalText())){"native_scope_does_not_cover_effect"}
                if(support["old_clause_id"]!=null)require(!replace && clauses[path]?.id==support["old_clause_id"]){"stale_native_clause_id"}
                supports[path]=support
            }
            require(supports.keys==operations.keys){"complete_native_change_sources_required"}
            for((path,support)in supports){val op=operations.getValue(path);val previous=if(replace)null else clauses[path]
                val spans=support.getValue("spans").goalList();val premises=support.getValue("premises").goalList().map{it.goalList().map{p->p.goalText()}}
                when(support["basis"]){
                    "explicit"->{require(spans.isNotEmpty() && support["rule_id"]==null && premises.isEmpty()){"invalid_native_explicit_source"}
                        if(op.operation=="remove")require(support["old_clause_id"]!=null){"native_removal_requires_current_clause"}}
                    "derived"->{val rule=rules[support["rule_id"]];require(path[0]=="field" && op.operation=="put" && rule!=null && spans.isEmpty() && support["old_clause_id"]==null){"invalid_native_derivation"}
                        val header=GoalCanonicalJson.parse(candidate.getValue(listOf("task",path[1]))).goalObject()
                        val wants=rule.getValue("premises").goalList().map{it.goalList()}.associate{listOf("field",path[1],it[0].goalText()) to GoalCanonicalJson.write(it[1])}
                        require(header["family"]==rule["family"] && path[2]==rule["output_field"] && candidate[path]==GoalCanonicalJson.write(rule["output"]) && premises.toSet()==wants.keys && premises.size==wants.size){"native_rule_not_bound_to_same_task"}
                        if(previous?.present==true && previous.origin!="derived_rule")require(GoalCanonicalJson.parse(old.getValue(path)).goalObject()["status"]=="unknown"){"native_derived_cannot_replace_independent_condition"}
                        for((parent,want)in wants)require(supports[parent]?.get("basis")=="explicit" && operations[parent]?.operation=="put" && candidate[parent]==want){"native_derivation_requires_explicit_current_premises"}}
                    "retire_derived"->{require(!replace && previous?.origin=="derived_rule" && op.operation=="remove" && spans.isEmpty() && support["rule_id"]==null && support["old_clause_id"]==previous.id && premises.isNotEmpty()){"native_derived_retirement_not_justified"}
                        require(premises.toSet().size==premises.size){"duplicate_native_retirement_premise"}
                        for(parent in premises)require(parent in previous.dependencies && parent in changed && previous.dependencies[parent]==generations.getOrDefault(parent,0) && supports[parent]?.get("basis")=="explicit"){"native_retirement_requires_changed_current_parent"}}
                    else->error("unknown_native_source_basis")
                }
            }
            if(!replace)for((path,c)in clauses)if(c.present && c.origin=="derived_rule" && c.dependencies.keys.any{it in changed})require(path in operations){"native_derived_dependent_requires_review"}
            val nextGenerations=if(replace)linkedMapOf() else LinkedHashMap(generations)
            for(path in changed)nextGenerations[path]=Math.addExact(nextGenerations.getOrDefault(path,0),1)
            val nextRegistry=if(replace)linkedMapOf() else LinkedHashMap(registry)
            for(path in candidate.keys)nextRegistry[pathKey(path)]=taskScope(path,candidate).sortedWith(GoalCanonicalJson.textOrder)
            val nextCatalogue=GoalCanonicalJson.digest(mapOf("native_policy" to policy,"schema_keys" to nextRegistry.keys.sortedWith(GoalCanonicalJson.textOrder),"field_tasks" to nextRegistry))
            val touched=operations.keys+candidate.keys.filter(::structural);val ids=touched.associateWith{UUID.randomUUID().toString()}
            val nextClauses=if(replace)linkedMapOf() else LinkedHashMap(clauses)
            val structuralParents=candidate.keys.filter{it[0] in setOf("task","constraint")}
            for(path in touched){val support=supports[path];val prior=if(replace)null else clauses[path]
                val origin=when(support?.get("basis")){null->"owner_structure";"derived"->"derived_rule";"retire_derived"->"retired_derived";else->if(path in candidate)"explicit_span" else "retired_explicit"}
                val parents=if(support==null)structuralParents else if(origin in setOf("derived_rule","retired_derived"))support.getValue("premises").goalList().map{it.goalList().map{v->v.goalText()}} else emptyList()
                val dependency=if(origin in setOf("derived_rule","owner_structure"))parents.associateWith{nextGenerations.getOrDefault(it,0)} else emptyMap()
                nextClauses[path]=Clause(ids.getValue(path),pathKey(path),path in candidate,nextGenerations.getOrDefault(path,0),origin,dependency,
                    parents.map{ids[it] ?: nextClauses.getValue(it).id},prior?.id,input.requestId,GoalCanonicalJson.digest(input.utterance),GoalCanonicalJson.digest(input.contextJson),input.catalogueDigest,support?.let(GoalCanonicalJson::write))
            }
            // Prepare all candidates before publishing the one current state under this owner lock.
            if(replace)roots.add(GoalCanonicalJson.write(state()))
            cells=candidate;generations=nextGenerations;clauses=nextClauses;registry=nextRegistry;catalogue=nextCatalogue
            for(c in nextClauses.values)clauseHistory[c.id]=c
            family=FAMILY;active=true;if(replace)root=Math.addExact(root,1)
            if(replace || changed.isNotEmpty())revision=Math.addExact(revision,1)
            settled=turnSequence;epoch=Math.addExact(epoch,1);pending=null;ready=true;sources[input.requestId]=input;jobs.clear()
            outcome(if(replace || changed.isNotEmpty())"APPLY" else "KEEP",if(replace)"complete_typed_new_root" else "typed_sparse_amendment",changed.map(::pathKey).sortedWith(GoalCanonicalJson.textOrder))
        }catch(error:Exception){outcome("ASK",error.message ?: "malformed_native_candidate")}
    }
    fun closeLocally():Outcome=synchronized(lock){
        jobs.clear()
        if(!active && pending==null)return@synchronized outcome("KEEP","already_closed")
        active=false;revision=Math.addExact(revision,1);settled=turnSequence;epoch=Math.addExact(epoch,1);pending=null;ready=false
        outcome("CLOSE","local_guidance_only_closed")
    }
    fun bindCompletionAuthority():Any=synchronized(lock){check(terminalAuthority==null){"completion_authority_already_bound"};Any().also{terminalAuthority=it}}
    fun complete(authority:Any, original:GuideGoalRef, evidenceDigest:String):Terminal?=synchronized(lock){
        if(authority!==terminalAuthority || currentRef()!=original || !evidenceDigest.matches(Regex("[a-f0-9]{64}")))return@synchronized null
        active=false;revision=Math.addExact(revision,1);epoch=Math.addExact(epoch,1);ready=false;jobs.clear()
        Terminal(UUID.randomUUID().toString(),original,epoch,revision,stateDigest(),evidenceDigest).also{terminal=it;terminals.add(it)}
    }
    private fun terminalCurrent(value:Terminal)=value==terminal && !active && pending==null && value.epoch==epoch && value.revision==revision && value.original.root==root && value.digest==stateDigest()
    fun consumeTerminal(authority:Any,value:Terminal,effect:()->Unit):Boolean=synchronized(lock){
        if(authority!==terminalAuthority || !terminalCurrent(value))false else{effect();true}
    }
    fun consumeLearning(authority:Any,value:Terminal,effect:()->Unit):Boolean=synchronized(lock){
        if(authority!==terminalAuthority || !terminalCurrent(value) || value.id in learned)false else{learned.add(value.id);effect();true}
    }
    fun consumeCurrent(reference:GuideGoalRef,effect:()->Unit):Boolean=synchronized(lock){
        if(currentRef()!=reference)false else{effect();true}
    }
}
