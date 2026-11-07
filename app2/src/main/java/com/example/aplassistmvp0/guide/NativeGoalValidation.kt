package com.example.aplassistmvp0.guide

import java.math.BigInteger

/** Declared task/effect consistency only. Never language, app truth or action permission. */
internal class NativeGoalValidation(schemaJson:String) {
    val schema=GoalCanonicalJson.parse(schemaJson).goalObject()
    val canonicalSchema=GoalCanonicalJson.write(schema)
    val families=schema.getValue("families").goalObject()
    data class Report(val status:String,val reason:String,val issues:List<String>)
    private data class Literal(val variable:String,val value:String,val equal:Boolean=true)
    private fun validQuantityNumber(number:String):Boolean {
        if(number.length !in 1..64 || !number.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")))return false
        // Preserve the decimal source string. Match the pinned reference's finite representation
        // bounds without rounding through Double or Android BigDecimal's narrower exponent type.
        val parts=number.lowercase().split('e');val mantissa=parts[0].removePrefix("+").removePrefix("-")
        val fractional=mantissa.substringAfter('.',"").length
        val exponent=(if(parts.size==2)BigInteger(parts[1]) else BigInteger.ZERO)-BigInteger.valueOf(fractional.toLong())
        val digits=mantissa.replace(".","").trimStart('0').length.coerceAtLeast(1)
        val adjusted=exponent+BigInteger.valueOf(digits.toLong()-1)
        return exponent>=BigInteger("-1999999999999999997") && adjusted<=BigInteger("999999999999999999")
    }
    fun validate(cells:Map<List<String>,String>):Report {
        require(cells.isNotEmpty() && cells.size<=512){"native_representation_budget_or_shape"}
        require(cells.entries.sumOf{GoalCanonicalJson.write(it.key).length+it.value.length}<=1_048_576){"native_representation_budget"}
        val stages=linkedSetOf<String>();val targets=linkedMapOf<String,Map<String,Any?>>()
        val headers=linkedMapOf<String,Map<String,Any?>>()
        val fields=linkedMapOf<String,LinkedHashMap<String,Map<String,Any?>>>()
        val constraints=mutableListOf<Map<String,Any?>>()
        var order:List<String>?=null
        for((path,raw)in cells){
            NativeGoalOwner.pathKey(path)
            val value=GoalCanonicalJson.parse(raw).goalObject();require(GoalCanonicalJson.write(value)==raw){"canonical_native_cell_required"}
            when(path[0]){
                "task_order"->{value.exact("task_ids");val ids=value.getValue("task_ids").goalList().map{it.goalText()}
                    require(ids.all(::goalLabel) && ids.toSet().size==ids.size){"invalid_task_list_order"};order=ids}
                "stage"->{require(value.exact("stage_id")["stage_id"]==path[1]){"stage_path_mismatch"};stages.add(path[1])}
                "target"->{value.exact("target_id","target_type","unit");require(value["target_id"]==path[1]){"target_path_mismatch"};targets[path[1]]=value}
                "task"->headers[path[1]]=value.exact("family","target_id","stage_id","coverage")
                "field"->{value.exact("status","value");val v=value["value"]
                    require(v==null || v is String || v is Map<*,*>){"native_slot_value_type"}
                    if(v is Map<*,*>)v.goalObject().exact("number","unit")
                    fields.getOrPut(path[1]){linkedMapOf()}[path[2]]=value}
                "constraint"->{value.exact("constraint_id","target_id","effect","value","scope","stage_id")
                    require(value["constraint_id"]==path[1]){"constraint_path_mismatch"};constraints.add(value)}
            }
        }
        require(headers.isNotEmpty() && headers.size<=32 && stages.isNotEmpty() && stages.size<=64 && targets.size<=64){"native_registry_budget_or_empty_goal"}
        val taskOrder=requireNotNull(order){"native_task_order_or_orphan_fields"}
        require(taskOrder.toSet()==headers.keys && fields.keys.all{it in headers}){"native_task_order_or_orphan_fields"}
        for((id,h)in headers){
            require(h["family"] in families && h["target_id"] in targets && h["stage_id"] in stages){"unbound_native_task_or_family"}
            require(h["coverage"]=="closed"){"native_task_coverage_unresolved"}
            val defs=families.getValue(h["family"].goalText()).goalObject().getValue("fields").goalObject()
            require(fields[id].orEmpty().keys.all{it in defs}){"unsupported_native_field"}
        }
        for(c in constraints)require(c["target_id"] in targets && (c["scope"]!="stage" || c["stage_id"] in stages)){"unbound_native_constraint"}
        val domains=linkedMapOf<String,List<String>>();val clauses=mutableListOf<List<Literal>>();val issues=linkedSetOf<String>()
        fun variable(vararg parts:String)=GoalCanonicalJson.write(parts.toList())
        fun strings(value:Any?)=value.goalList().map{it.goalText()}
        fun matches(condition:Map<String,Any?>,fixed:Map<String,Any?>)=condition.all{(k,v)->fixed[k]==v}
        try {
            for((id,t)in targets){require(goalLabel(id) && goalLabel(t["target_type"].goalText()))
                require(t["unit"]==null || goalLabel(t["unit"].goalText()))}
            for(c in constraints){
                val target=targets.getValue(c["target_id"].goalText())
                val effects=schema.getValue("target_effects").goalObject()[target["target_type"]].goalObject()
                val effect=c["effect"].goalText();require(effect in effects && c["value"] in strings(effects[effect]))
                val scopeStages=when(c["scope"]){
                    "all_stages"->{require(c["stage_id"]==null);stages.toList()}
                    "stage"->listOf(c["stage_id"].goalText())
                    else->error("constraint_scope_invalid")
                }
                for(stage in scopeStages){val key=variable("effect",c["target_id"].goalText(),stage,effect)
                    domains[key]=strings(effects[effect]);clauses.add(listOf(Literal(key,c["value"].goalText())))}
            }
            for(id in taskOrder){
                val h=headers.getValue(id);val family=families.getValue(h["family"].goalText()).goalObject()
                val target=targets.getValue(h["target_id"].goalText());require(target["target_type"]==family["target_type"])
                val defs=family.getValue("fields").goalObject();val supplied=fields[id].orEmpty();val fixed=linkedMapOf<String,Any?>()
                for((key,slot)in supplied){val spec=defs.getValue(key).goalObject();val status=slot["status"].goalText();val value=slot["value"]
                    require(status in setOf("known","unknown","any"));require(status=="known" || value==null)
                    require(status!="any" || spec["allow_any"]==true)
                    when(spec["type"]){
                        "quantity"->{if(status=="known"){
                            val q=value.goalObject().exact("number","unit");val number=q["number"].goalText()
                            require(validQuantityNumber(number) && goalLabel(q["unit"].goalText()))
                            if(q["unit"]!=target["unit"])issues.add("quantity_unit_unbound:$id:$key");fixed[key]=value
                        }}
                        "reference"->{if(status=="known"){require(goalLabel(value.goalText()));issues.add("unverified_reference:$id:$key")}}
                        else->{val values=strings(spec["values"]);if(status=="known")require(value is String && value in values)
                            val name=variable("field",id,key);domains[name]=values.map{"known:$it"}+"unknown"+(if(spec["allow_any"]==true)listOf("any") else emptyList())
                            clauses.add(listOf(Literal(name,if(status=="known")"known:$value" else status)))
                            if(status=="known")fixed[key]=value}
                    }
                }
                val required=strings(family["required"]).toMutableList()
                for(c in family.getValue("conditional_required").goalList().map{it.goalObject()})
                    if(matches(c.getValue("when").goalObject(),fixed))required.addAll(strings(c["fields"]))
                for(key in required)if(supplied[key]?.get("status")!="known")issues.add("required_value_unfixed:$id:$key")
                for(c in (family["conditional_inactive"]?:emptyList<Any?>()).goalList().map{it.goalObject()})
                    if(matches(c.getValue("when").goalObject(),fixed))for(key in strings(c["fields"]))
                        if(supplied[key]?.get("status")=="known")issues.add("inactive_field_has_requirement:$id:$key")
                for(r in family.getValue("relations").goalList().map{it.goalObject()}){
                    val condition=r.getValue("when").goalObject();val missing=condition.keys.filter{it !in supplied}
                    if(missing.isNotEmpty()){
                        if(missing.any{it in required})issues.add("required_rule_input_missing:$id:${r["id"]}");continue
                    }
                    val literals=condition.map{(key,v)->Literal(variable("field",id,key),"known:${v.goalText()}",false)}.toMutableList()
                    if("effect" in r){val effect=r["effect"].goalText();val name=variable("effect",h["target_id"].goalText(),h["stage_id"].goalText(),effect)
                        val domain=strings(family.getValue("effects").goalObject()[effect]);require(name !in domains || domains[name]==domain)
                        domains[name]=domain;literals.add(Literal(name,r["value"].goalText()))
                    }else require(r["forbid"]==true)
                    clauses.add(literals)
                }
            }
            require(domains.values.all{it.isNotEmpty() && it.toSet().size==it.size})
            require(clauses.flatten().all{it.variable in domains && it.value in domains.getValue(it.variable)})
        }catch(_:Exception){return Report("INVALID","malformed_or_unbound_typed_proposal",emptyList())}
        if(domains.size>128 || clauses.size>2048 || clauses.any{it.size>64})return Report("INDETERMINATE","representation_budget_exceeded",issues.toList())
        var nodes=0
        fun search(assignment:MutableMap<String,String>):String {
            if(nodes>=10_000)return "UNKNOWN";nodes++
            val unassigned=linkedSetOf<String>()
            for(clause in clauses){var satisfied=false;val pending=mutableListOf<String>()
                for(lit in clause){val value=assignment[lit.variable]
                    if(value==null)pending.add(lit.variable) else if((value==lit.value)==lit.equal){satisfied=true;break}}
                if(satisfied)continue;if(pending.isEmpty())return "UNSAT";unassigned.addAll(pending)
            }
            if(unassigned.isEmpty())return "SAT"
            val variable=unassigned.minWithOrNull(Comparator{a,b->val n=domains.getValue(a).size.compareTo(domains.getValue(b).size);if(n==0)GoalCanonicalJson.textOrder.compare(a,b) else n})!!
            var unknown=false
            for(value in domains.getValue(variable)){assignment[variable]=value;val result=search(assignment);assignment.remove(variable)
                if(result=="SAT")return "SAT";if(result=="UNKNOWN")unknown=true}
            return if(unknown)"UNKNOWN" else "UNSAT"
        }
        return when(search(linkedMapOf())){
            "UNKNOWN"->Report("INDETERMINATE","search_budget_exhausted",issues.toList())
            "UNSAT"->Report("CONTRADICTION","supported_constraints_have_no_joint_completion",issues.toList())
            else->if(issues.isEmpty())Report("NO_CONTRADICTION","supported_fragment_has_a_completion",emptyList())
                  else Report("INDETERMINATE","unsupported_fragment_remains",issues.toList())
        }
    }
}
