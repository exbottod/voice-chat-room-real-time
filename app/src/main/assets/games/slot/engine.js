/* Mesin slot offline: reels dan tabel bayar dari Cherry Hot (AGT), kode berlisensi MIT.
   Dijalankan di dalam app tanpa server. RTP diatur sekitar 94 persen. */
var ENG = {"reels":[[1,2,3,3,3,3,3,3,8,1,5,5,5,5,5,5,2,1,7,7,7,7,7,7,2,6,6,6,6,6,6,4,4,4,4,4,4,8,7,7,7],[7,7,7,7,7,7,6,6,6,6,6,6,2,8,1,5,5,5,5,5,5,8,1,7,7,7,2,1,4,4,4,4,4,4,3,3,3,3,3,3,2],[7,7,7,7,7,7,7,7,7,1,1,6,6,6,6,6,6,8,4,4,4,4,4,4,8,2,1,2,5,5,5,5,5,5,3,3,3,3,3,3,2,2],[2,1,4,4,4,4,4,4,2,1,7,7,7,5,5,5,5,5,5,2,8,7,7,7,7,7,7,8,3,3,3,3,3,3,1,6,6,6,6,6,6],[4,4,4,4,4,4,3,3,3,3,3,3,5,5,5,5,5,5,1,7,7,7,7,7,7,1,8,7,7,7,2,2,6,6,6,6,6,6,2,1,8]],"lines":[[2,2,2,2,2],[1,1,1,1,1],[3,3,3,3,3],[1,2,3,2,1],[3,2,1,2,3]],"pay":{"1":[0,0,42000,420000,2100000],"2":[0,0,16800,168000,420000],"3":[0,0,10080,25200,84000],"4":[0,0,8400,21000,84000],"5":[0,0,8400,21000,84000],"6":[0,2100,6720,16800,67200],"7":[0,0,2100,8400,42000]},"scat":[0,0,840,5040,25200]};
ENG.SYM = {1:'strawberry',2:'grapes',3:'apricot',4:'pear',5:'orange',6:'cherry',7:'apple',8:'lucky_seven'};
ENG.LABEL = {1:'S',2:'G',3:'A',4:'P',5:'O',6:'C',7:'A7',8:'77'};
ENG.spin = function(){
  var grid=[];
  for(var c=0;c<ENG.reels.length;c++){
    var col=ENG.reels[c];
    var i=Math.floor(Math.random()*col.length);
    var rows=[];
    for(var r=0;r<3;r++){rows.push(col[(i+r) % col.length]);}
    grid.push(rows);
  }
  var win=0, hits=[];
  for(var l=0;l<ENG.lines.length;l++){
    var ln=ENG.lines[l], sym=null, cnt=0;
    for(var c2=0;c2<5;c2++){
      var s=grid[c2][ln[c2]-1];
      if(s===8) break;
      if(sym===null) sym=s;
      if(s===sym) cnt++; else break;
    }
    if(sym!==null && cnt>=2){
      var tbl=ENG.pay[String(sym)];
      var val=tbl?tbl[cnt-1]:0;
      if(val>0){ win+=val; hits.push({line:l+1,sym:sym,cnt:cnt,val:val}); }
    }
  }
  var sc=0;
  for(var c3=0;c3<5;c3++){for(var r2=0;r2<3;r2++){if(grid[c3][r2]===8)sc++;}}
  if(sc>=3){
    var sv=ENG.scat[Math.min(sc,5)-1]||0;
    if(sv>0){win+=sv;hits.push({line:0,sym:8,cnt:sc,val:sv});}
  }
  return {grid:grid, win:win, hits:hits, scatters:sc};
};
