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
    pending = (window.ENG ? ENG.spin() : null);
    if(!pending){return;}
    for(var c=0;c<cols.length;c++){
        var icons=cols[c].querySelectorAll('.icon img');
        for(var x=0;x<icons.length;x++){
            icons[x].setAttribute('src','items/'+ICONS[Math.floor(Math.random()*ICONS.length)]+'.png');
        }
        for(var r=0;r<3;r++){
            icons[icons.length-3+r].setAttribute('src','items/'+ENG.SYM[pending.grid[c][r]]+'.png');
        }
    }
}

function wbSettle(){
  if(!pending){wbMsg('Coba spin lagi');return;}
  var win=pending.win||0;
  if(win>0){
    if(window.AndroidGame&&AndroidGame.win){var nb=Number(AndroidGame.win(win));if(nb>=0)balance=nb;else balance+=win;}
    else balance+=win;
    wbRender();
    var best=0,label='';
    (pending.hits||[]).forEach(function(h){
      if(h.val>best){best=h.val;label=(h.line===0?('scatter '+h.cnt+'x'):('baris '+h.line+' · '+h.cnt+' sama'));}
    });
    wbMsg('Menang! +'+fmtC(win)+' coin'+(label?(' · '+label):''));
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