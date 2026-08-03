# Pair Startpoint/Endpoint/slack from OpenSTA full-format output and cluster by
# bit-stripped (start_group -> end_group) signature, keeping worst slack + count.
function strip(s){
  gsub(/\[[0-9]+\]/,"",s);        # bus bit index  foo[3]
  gsub(/_[0-9]+_/,"_",s);         # mangled bit     foo_3_
  gsub(/\$[0-9]+/,"",s);          # yosys temp id
  gsub(/ \(.*\)$/,"",s);          # trailing "(input port ...)" etc
  sub(/^[ \t]+/,"",s); sub(/[ \t]+$/,"",s);
  return s;
}
/^Startpoint:/ { sp=$0; sub(/^Startpoint: /,"",sp); sp=strip(sp); have_sp=1; next }
/^Endpoint:/   { ep=$0; sub(/^Endpoint: /,"",ep);   ep=strip(ep); have_ep=1; next }
/slack \(/ {
  if(have_sp&&have_ep){
    sl=$NF+0;
    key=sp" -> "ep;
    if(!(key in seen) || sl<worst[key]){ worst[key]=sl }
    if(!(key in seen)){ order[++n]=key }
    seen[key]++; cnt[key]++;
  }
  have_sp=0; have_ep=0; next
}
END{
  # sort keys by worst slack ascending (most critical first)
  for(i=1;i<=n;i++) keys[i]=order[i];
  for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if(worst[keys[j]]<worst[keys[i]]){t=keys[i];keys[i]=keys[j];keys[j]=t}
  printf("%-9s %-5s  %s\n","SLACK","#BITS","LOGICAL PATH (start_group -> end_group)");
  for(i=1;i<=n;i++){ k=keys[i]; printf("%-9.3f %-5d  %s\n",worst[k],cnt[k],k) }
}
