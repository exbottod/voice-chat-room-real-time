/* ====== sambungan coin akun (jembatan Android) ====== */
var CHIPS=[];
var balance=(window.AndroidGame&&AndroidGame.balance)?Number(AndroidGame.balance()):0;
var PRIZE={cherry:100000,lemon:150000,orange:200000,apple:300000,banana:400000,pear:600000,grapes:800000,watermelon:1000000,apricot:1500000,lucky_seven:5000000};
var PAIR={cherry:10000,lemon:15000,orange:20000,apple:30000,banana:40000,pear:60000,grapes:80000,watermelon:100000,apricot:150000,lucky_seven:500000};
var pending=null;
function fmtC(n){n=Math.floor(n||0);return n.toLocaleString('id-ID');}
function wbMsg(m){var e=document.getElementById('wbMsg');if(e)e.textContent=m;}
function wbRender(){
  var b=document.getElementById('wbBalance');if(b)b.textContent=fmtC(balance);
}
function wbSetBalance(v){if(v>=0){balance=v;wbRender();}}
document.addEventListener('DOMContentLoaded',wbRender);

const ICONS = [
    'apple', 'apricot', 'banana', 'big_win', 'cherry', 'grapes', 'lemon', 'lucky_seven', 'orange', 'pear', 'strawberry', 'watermelon',
];

/**
 * @type {number} The minimum spin time in seconds
 */
const BASE_SPINNING_DURATION = 2.7;

/**
 * @type {number} The additional duration to the base duration for each row (in seconds).
 * It makes the typical effect that the first reel ends, then the second, and so on...
 */
const COLUMN_SPINNING_DURATION = 0.3;


var cols;


window.addEventListener('DOMContentLoaded', function(event) {
    cols = document.querySelectorAll('.col');

    setInitialItems();
});

function setInitialItems() {
    let baseItemAmount = 40;

    for (let i = 0; i < cols.length; ++i) {
        let col = cols[i];
        let amountOfItems = baseItemAmount + (i * 3); // Increment the amount for each column
        let elms = '';
        let firstThreeElms = '';

        for (let x = 0; x < amountOfItems; x++) {
            let icon = getRandomIcon();
            let item = '<div class="icon" data-item="' + icon + '"><img src="items/' + icon + '.png"></div>';
            elms += item;

            if (x < 3) firstThreeElms += item; // Backup the first three items because the last three must be the same
        }
        col.innerHTML = elms + firstThreeElms;
    }
}

/**
 * Called when the start-button is pressed.
 *
 * @param elem The button itself
 */
function spin(elem) {
    wbMsg('Roda berputar…');
    let duration = BASE_SPINNING_DURATION + randomDuration();

    for (let col of cols) { // set the animation duration for each column
        duration += COLUMN_SPINNING_DURATION + randomDuration();
        col.style.animationDuration = duration + "s";
    }

    // disable the start-button
    elem.setAttribute('disabled', true);

    // set the spinning class so the css animation starts to play
    document.getElementById('container').classList.add('spinning');

    // set the result delayed
    // this would be the right place to request the combination from the server
    window.setTimeout(setResult, BASE_SPINNING_DURATION * 1000 / 2);

    window.setTimeout(function () {
        // after the spinning is done, remove the class and enable the button again
        document.getElementById('container').classList.remove('spinning');
        elem.removeAttribute('disabled');
        wbSettle();
    }.bind(elem), duration * 1000);
}

/**
 * Sets the result items at the beginning and the end of the columns
 */
function setResult() {
    // Tentukan hasil lebih dulu: 8% tiga sama, 47% dua sama, sisanya belum menang.
    var roll=Math.random();
    var symbols=[];
    var mk=function(){return getRandomIcon();};
    if(roll<0.08){
        var s=mk();
        symbols=[mk(),s,s,s,mk()];
    }else if(roll<0.55){
        var p=mk();
        symbols=[mk(),p,p,mk(),mk()];
        // pastikan lima simbol tidak semuanya sama
        for(var i=0;i<symbols.length;i++){if(symbols[i]===symbols[1]&&i!==1&&i!==2){symbols[i]=ICONS[(ICONS.indexOf(p)+1+i)%ICONS.length];}}
    }else{
        symbols=[mk(),mk(),mk(),mk(),mk()];
        for(var j=0;j<symbols.length;j++){
            if(symbols.filter(function(x){return x===symbols[j]}).length>1){symbols[j]=ICONS[(ICONS.indexOf(symbols[j])+1)%ICONS.length];}
        }
    }
    pending={symbols:symbols};

    // Isi tiap gulungan; yang terlihat adalah tiga item terakhir, baris tengah = item kedua dari belakang.
    for(var c=0;c<cols.length;c++){
        var col=cols[c];
        var icons=col.querySelectorAll('.icon img');
        var sym=symbols[c];
        for(var x=0;x<icons.length;x++){
            icons[x].setAttribute('src','items/'+getRandomIcon()+'.png');
        }
        icons[icons.length-3].setAttribute('src','items/'+getRandomIcon()+'.png');
        icons[icons.length-2].setAttribute('src','items/'+sym+'.png');
        icons[icons.length-1].setAttribute('src','items/'+getRandomIcon()+'.png');
        for(var y=0;y<3;y++){icons[y].setAttribute('src','items/'+getRandomIcon()+'.png');}
    }
}

function wbSettle(){
  var symbols=(pending&&pending.symbols)||[];
  if(!symbols.length){wbMsg('Coba spin lagi');return;}
  var counts={};
  symbols.forEach(function(s){counts[s]=(counts[s]||0)+1;});
  var best=null,bestN=0;
  Object.keys(counts).forEach(function(k){if(counts[k]>bestN){bestN=counts[k];best=k;}});
  var payout=0,label='';
  if(bestN>=3){payout=PRIZE[best]||100000;label='3 sama';}
  else if(bestN===2){payout=PAIR[best]||10000;label='2 sama';}
  if(payout>0){
    if(window.AndroidGame&&AndroidGame.win){var nb=Number(AndroidGame.win(payout));if(nb>=0)balance=nb;else balance+=payout;}
    else balance+=payout;
    wbRender();wbMsg('Menang '+label+' · +'+fmtC(payout)+' coin');
  }else{
    wbMsg('Belum menang · coba spin lagi');
  }
  pending=null;
}

function getRandomIcon() {
    return ICONS[Math.floor(Math.random() * ICONS.length)];
}

/**
 * @returns {number} 0.00 to 0.09 inclusive
 */
function randomDuration() {
    return Math.floor(Math.random() * 10) / 100;
}