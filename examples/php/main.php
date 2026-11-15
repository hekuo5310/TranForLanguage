<?php
Tranfor::on('PlayerJoin', function ($player) {
    Tranfor::sendMessage($player, 'Welcome from PHP!');
});

Tranfor::run();
