/* ====== sambungan coin akun (jembatan Android) ====== */
var CHIPS=[];
var balance=(window.AndroidGame&&AndroidGame.balance)?Number(AndroidGame.balance()):0;
var PRIZE={cherry:50000,lemon:75000,orange:100000,apple:150000,banana:200000,pear:300000,grapes:400000,watermelon:500000,apricot:750000,lucky_seven:2000000};
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
    for (let col of cols) {

        // generate 3 random items
        let results = [
            getRandomIcon(),
            getRandomIcon(),
            getRandomIcon()
        ];

        let icons = col.querySelectorAll('.icon img');
        // replace the first and last three items of each column with the generated items
        for (let x = 0; x < 3; x++) {
            icons[x].setAttribute('src', 'items/' + results[x] + '.png');
            icons[(icons.length - 3) + x].setAttribute('src', 'items/' + results[x] + '.png');
        }
    }
}

function wbSettle(){
  var shown=[];
  for(var i=0;i<cols.length;i++){
    var col=cols[i];
    var box=col.parentElement.getBoundingClientRect();
    var best=null,bd=1e9;
    var list=col.querySelectorAll('.icon');
    for(var k=0;k<list.length;k++){
      var r=list[k].getBoundingClientRect();
      var d=Math.abs((r.top+r.height/2)-(box.top+box.height/2));
      if(d<bd){bd=d;best=list[k];}
    }
    shown.push(best?best.getAttribute('data-item'):null);
  }
  var win=shown[0]&&shown[0]===shown[1]&&shown[1]===shown[2];
  if(win){
    var payout=PRIZE[shown[0]]||50000;
    if(window.AndroidGame&&AndroidGame.win){var nb=Number(AndroidGame.win(payout));if(nb>=0)balance=nb;else balance+=payout;}
    else balance+=payout;
    wbRender();wbMsg('Menang! +'+fmtC(payout)+' coin');
  }else{
    wbMsg('Belum menang · coba lagi');
  }
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