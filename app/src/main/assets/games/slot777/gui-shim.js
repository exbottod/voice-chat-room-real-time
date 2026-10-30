/* Pengganti lil-gui: panel debug tidak dipakai di app. */
function Chain(){ }
['addFolder','add','onFinishChange','onChange','reset','title','close','open','listen','name','destroy'].forEach(function(k){
  Chain.prototype[k]=function(){ return new Chain(); };
});
Object.defineProperty(Chain.prototype,'domElement',{get:function(){return document.createElement('div');}});
export default function GUI(){ return new Chain(); }
