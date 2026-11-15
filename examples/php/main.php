<?php
TranForLanguage::on('PlayerJoin', function ($player) {
    TranForLanguage::sendMessage($player, 'Welcome from PHP!');
});

TranForLanguage::run();
