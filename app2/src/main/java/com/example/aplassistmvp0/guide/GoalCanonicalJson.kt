package com.example.aplassistmvp0.guide

import java.security.MessageDigest
import java.util.Collections

/** Canonical native-goal values. Quantities use decimal STRINGS, not JSON floating point. */
internal object GoalCanonicalJson {
    val textOrder = Comparator<String> { a, b ->
        val aa=a.codePoints().toArray();val bb=b.codePoints().toArray()
        var result=0;var i=0
        while(i<minOf(aa.size,bb.size) && result==0){result=aa[i].compareTo(bb[i]);i++}
        if(result==0)aa.size.compareTo(bb.size) else result
    }
    fun validText(text:String):Boolean {
        var i=0
        while(i<text.length){val c=text[i++]
            if(c.isHighSurrogate()){if(i==text.length || !text[i++].isLowSurrogate())return false}
            else if(c.isLowSurrogate())return false
        }
        return true
    }
    fun write(value:Any?):String=when(value){
        null->"null"
        is String->{require(validText(value)){"invalid_unicode"};buildString{
            append('"');for(c in value)append(when(c){
                '"'->"\\\"";'\\'->"\\\\";'\b'->"\\b";'\u000c'->"\\f";'\n'->"\\n";'\r'->"\\r";'\t'->"\\t"
                else->if(c<' ')"\\u%04x".format(c.code) else c.toString()
            });append('"')
        }}
        is Boolean->value.toString()
        is Int,is Long->value.toString()
        is List<*>->value.joinToString(",","[","]"){write(it)}
        is Map<*,*>->{require(value.keys.all{it is String});value.keys.map{it as String}.sortedWith(textOrder)
            .joinToString(",","{","}"){write(it)+":"+write(value[it])}}
        else->error("unsupported_native_json_type")
    }
    fun digest(value:Any?):String=MessageDigest.getInstance("SHA-256").digest(write(value).toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}
    fun parse(text:String):Any? {
        require(text.length<=1_048_576 && validText(text)){"native_json_budget_or_unicode"}
        class Reader {
            var i=0
            fun space(){while(i<text.length && text[i] in " \r\n\t")i++}
            fun string():String {check(text[i++]=='"');val out=StringBuilder()
                while(i<text.length){val c=text[i++];if(c=='"'){val s=out.toString();require(validText(s));return s}
                    if(c=='\\'){require(i<text.length);val e=text[i++];out.append(when(e){
                        '"','\\','/'->e;'b'->'\b';'f'->'\u000c';'n'->'\n';'r'->'\r';'t'->'\t'
                        'u'->{require(i+4<=text.length);val h=text.substring(i,i+4);require(h.all{it in "0123456789abcdefABCDEF"});i+=4;h.toInt(16).toChar()}
                        else->error("invalid_json_escape")
                    })}else{require(c>=' ');out.append(c)}
                };error("unterminated_json_string")
            }
            fun value(depth:Int=0):Any? {require(depth<=64);space();require(i<text.length)
                return when(text[i]){
                    '"'->string()
                    '{'->{i++;space();val result=linkedMapOf<String,Any?>()
                        if(i<text.length && text[i]=='}'){i++;Collections.unmodifiableMap(result)}else{
                            while(true){space();require(i<text.length && text[i]=='"');val key=string();require(key !in result){"duplicate_json_key"}
                                space();require(i<text.length && text[i++]==':');result[key]=value(depth+1);space();require(i<text.length)
                                val c=text[i++];if(c=='}')break;require(c==',')
                            };Collections.unmodifiableMap(result)
                        }}
                    '['->{i++;space();val result=mutableListOf<Any?>()
                        if(i<text.length && text[i]==']'){i++;Collections.unmodifiableList(result)}else{
                            while(true){result.add(value(depth+1));space();require(i<text.length);val c=text[i++];if(c==']')break;require(c==',')}
                            Collections.unmodifiableList(result)
                        }}
                    't'->{require(text.startsWith("true",i));i+=4;true}
                    'f'->{require(text.startsWith("false",i));i+=5;false}
                    'n'->{require(text.startsWith("null",i));i+=4;null}
                    else->{val start=i;if(text[i]=='-')i++;require(i<text.length && text[i].isDigit())
                        if(text[i]=='0')i++ else{require(text[i] in '1'..'9');while(i<text.length && text[i] in '0'..'9')i++}
                        text.substring(start,i).toLong()}
                }
            }
        }
        val reader=Reader();val value=reader.value();reader.space();require(reader.i==text.length){"trailing_or_noninteger_json"};return value
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.goalObject():Map<String,Any?> =this as? Map<String,Any?> ?: error("goal_object_required")
internal fun Any?.goalList():List<Any?> =this as? List<Any?> ?: error("goal_list_required")
internal fun Any?.goalText():String=this as? String ?: error("goal_string_required")
internal fun Any?.goalLong():Long=this as? Long ?: (this as? Int)?.toLong() ?: error("goal_integer_required")
internal fun Map<String,Any?>.exact(vararg keys:String):Map<String,Any?> {require(this.keys==keys.toSet()){ "goal_exact_fields_required" };return this}
internal fun goalLabel(s:String)=s.isNotBlank()
