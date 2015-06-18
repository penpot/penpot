$(document).ready(function() {

	// MENU
	function toggleNav() {
		var mainNav = $('.main-nav');
		var content = $('.content');
		var navLinks = $('.main-nav ul li span');
		var logoWord = $('.word');

		mainNav.mouseover(function() {
			$(this).addClass("nav-toggle");
			navLinks.addClass("hide");
			logoWord.addClass("hide");
			content.addClass("content-toggle");
		});

		mainNav.mouseleave(function() {
			$(this).removeClass("nav-toggle");
			navLinks.removeClass("hide");
			logoWord.removeClass("hide");
			content.removeClass("content-toggle");
		});
	}
	toggleNav();

	// CARD SWITCH
	function cardSwitch() {
		var card1 = $('#card1');
		var card2 = $('#card2');
		var card3 = $('#card3');
		var card4 = $('#card4');
		var cardBtn1 = $('#cardBtn1');
		var cardBtn2 = $('#cardBtn2');
		var cardBtn3 = $('#cardBtn3');
		var cardBtn4 = $('#cardBtn4');
		var allCards = $('.card-base');
		var allCardsBtn = $('.card-select li');

		cardBtn1.click(function() {
			allCards.addClass("hidden");
			card1.removeClass("hidden");
			allCardsBtn.removeClass("current");
			cardBtn1.addClass("current");
		});

		cardBtn2.click(function() {
			allCards.addClass("hidden");
			card2.removeClass("hidden");
			allCardsBtn.removeClass("current");
			cardBtn2.addClass("current");
		});

		cardBtn3.click(function() {
			allCards.addClass("hidden");
			card3.removeClass("hidden");
			allCardsBtn.removeClass("current");
			cardBtn3.addClass("current");
		});

		cardBtn4.click(function() {
			allCards.addClass("hidden");
			card4.removeClass("hidden");
			allCardsBtn.removeClass("current");
			cardBtn4.addClass("current");
		});
	}
	cardSwitch();

});
