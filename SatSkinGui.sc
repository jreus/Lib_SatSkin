/*

Abstraction for Satellite-Skin GUI

Rule of thumb: classes should never be more than 400 lines, else you got a problem!

*/


// This one builds signal monitors for inputs and outputs..
SatSkinGui {
	classvar tx_r = 1.0, tx_g = 1.0, tx_b = 0.0; // tx colors

	var <gui_win, <gui_pads, <gui_scopes;
	var <gui_meter, <gui_freqScopes, <gui_numberboxes, <gui_faders;
	var osc_from_server;
	var synth_monitor;
 	var satskin;

	*new {|thesatskin|
		^super.new.init(thesatskin);
	}

	init {|thesatskin|
		satskin = thesatskin;
	}

	setupGui {|xpos, ypos|
 		var server = satskin.server;
		{
			this.setupMonitors();
			server.sync();
			this.makeGui(xpos, ypos);
			this.freqScopesActive(true);
		}.fork(AppClock);
	}

	*amp2color {|amp, r, g, b, rfixed=false, gfixed=false, bfixed=false|
		var result;
		amp = amp.linlin(0.0, 1.0, 0.0, 1.0);
		if(rfixed.not) { r = r * amp; };
		if(gfixed.not) { g = g * amp; };
		if(bfixed.not) { b = b * amp; };
		result = Color.new(r, g, b);
		^result;
	}

	// Make necessary monitoring synths to relay signal info back to the gui
	setupMonitors {
		var server = satskin.server;
		// osc_receiver for getting signal info back from the server
		osc_from_server = OSCdef(\satskinguilistener, {|msg|
			var micLamp, micRamp, spkLamp, spkRamp;
			var rx1amp, rx2amp;
			var tx1amp, tx2amp;
			var rx1tx, rx2tx;
			rx1amp = msg[3]; rx2amp = msg[4]; tx1amp = msg[5]; tx2amp = msg[6];
			micLamp = msg[7]; micRamp = msg[8]; spkLamp = msg[9]; spkRamp = msg[10];

			{
				if(gui_win.notNil) {
					gui_pads['RX1'].background = SatSkinGui.amp2color(rx1amp, 0, 0.6, 1.0);
					gui_pads['RX2'].background = SatSkinGui.amp2color(rx2amp, 0, 0.6, 1.0);
					gui_pads['TX1'].background = SatSkinGui.amp2color(tx1amp, 0.4, 0.7, 1.0);
					gui_pads['TX2'].background = SatSkinGui.amp2color(tx2amp, 0.4, 0.7, 1.0);

					gui_pads['M_L'].background = SatSkinGui.amp2color(micLamp, 0.5, 1.0, 1.0);
					gui_pads['M_R'].background = SatSkinGui.amp2color(micRamp, 0.5, 1.0, 1.0);
					gui_pads['S_L'].background = SatSkinGui.amp2color(spkLamp, 1.0, 1.0, 0.5);
					gui_pads['S_R'].background = SatSkinGui.amp2color(spkRamp, 1.0, 1.0, 0.5);


					rx1tx = SatSkin.dRX1.getnSynchronous(4);
					gui_pads['RX1_TX1'].background = SatSkinGui.amp2color(rx1tx[0], tx_r, tx_g, tx_b);
					gui_pads['RX1_TX2'].background = SatSkinGui.amp2color(rx1tx[1], tx_r, tx_g, tx_b);
					gui_numberboxes['RX1_TX1'].string = "RX1_TX1\n" + rx1tx[0].trunc(0.0001);
					gui_numberboxes['RX1_TX2'].string = "RX1_TX2\n" + rx1tx[1].trunc(0.0001);

					rx2tx = SatSkin.dRX2.getnSynchronous(4);
					gui_pads['RX2_TX1'].background = SatSkinGui.amp2color(rx2tx[0], tx_r, tx_g, tx_b);
					gui_pads['RX2_TX2'].background = SatSkinGui.amp2color(rx2tx[1], tx_r, tx_g, tx_b);
					gui_numberboxes['RX2_TX1'].string = "RX2_TX1\n" + rx2tx[0].trunc(0.0001);
					gui_numberboxes['RX2_TX2'].string = "RX2_TX2\n" + rx2tx[1].trunc(0.0001);

				};
			}.fork(AppClock);
		}, '/satskingui_signals');

		// MONITOR SYNTH - CAPTURES IN/OUT SIGNALS AND SENDING THEM TO THE GUI
		synth_monitor = {|data_rate=20|
			var rx1, rx2, rx1amp, rx2amp;
			var tx1, tx2, tx1amp, tx2amp, trig;
			var micLamp, micRamp, spkLamp, spkRamp;
			var monitor_sig;
			trig = Impulse.kr(data_rate);

			// Incoming signals RX1 & 2
			rx1 = InFeedback.ar(SatSkin.bRX1);
			rx2 = InFeedback.ar(SatSkin.bRX2);

			// Filter out HUM & High Frequencies
			rx1 = LPF.ar(HPF.ar(rx1, 100), 16000);
			rx2 = LPF.ar(HPF.ar(rx2, 100), 16000);
			// Amplitude values for RX1 & 2
			rx1amp = Amplitude.ar(rx1, 0.01, 0.01);
			rx2amp = Amplitude.ar(rx2, 0.01, 0.01);

			// Signals on tx1 & tx2 (outputs 2,3)
			tx1 = InFeedback.ar(SatSkin.bTX1);
			tx2 = InFeedback.ar(SatSkin.bTX2);
			// Amplitude values for TX1 & 2 (remove transmission signal outputs)
			tx1amp = Amplitude.kr(BLowPass4.ar(tx1, 17000), 0.01, 0.01);
			tx2amp = Amplitude.kr(BLowPass4.ar(tx2, 17000), 0.01, 0.01);

			// Amplitude values for speaker outputs & microphone inputs
			micLamp = Amplitude.kr(InFeedback.ar(SatSkin.bMIC_L), 0.01, 0.01);
			micRamp = Amplitude.kr(InFeedback.ar(SatSkin.bMIC_R), 0.01, 0.01);
			spkLamp = Amplitude.kr(InFeedback.ar(SatSkin.bSPK_L), 0.01, 0.01);
			spkRamp = Amplitude.kr(InFeedback.ar(SatSkin.bSPK_R), 0.01, 0.01);

			SendReply.kr(trig, '/satskingui_signals',
				[A2K.kr(rx1amp), A2K.kr(rx2amp), tx1amp, tx2amp, micLamp, micRamp, spkLamp, spkRamp]);
		}.play(server);
	}

	makeGui {|xpos, ypos|
		var chan = 0, tmp;
		if(gui_win.isNil) {
			var labelfont, labelcolor, labelbgcolor;
			labelfont = Font.new("Code", 10, italic: true);
			labelcolor = Color.white;
			labelbgcolor = Color.black;

			gui_pads = ();
			// Installation Drawing
			gui_win = Window.new("SatSkin", Rect(xpos, ypos, 800, 900));
			[
				['TX1', 60, 550+10, 190, 120],
				['TX2', 60, 550+135, 190, 120],
				['RX1', 60+200, 550+10, 190, 120],
				['RX1_TX1', 60+200, 550+10, 190 / 2.3, 120],
				['RX1_TX2', 60+200+190-(190 / 2.3), 550+10, 190 / 2.3, 120],
				['RX2', 60+200, 550+135, 190, 120],
				['RX2_TX1', 60+200, 550+135, 190/2.3, 120],
				['RX2_TX2', 60+200+190-(190/2.3), 550+135, 190/2.3, 120],
			].do {|desc, elementnum|
				var element;
				element = StaticText.new(gui_win, Rect(desc[1], desc[2], desc[3], desc[4]));
				element.string_(desc[0]).align_(\center).stringColor_(Color.white).background_(Color.black);
				gui_pads.put(desc[0], element);
			};

			[
				['S_L', 0+10, 560+100, 25, 60],
				['S_R', 0+500-25, 560+100, 25, 60],
				['M_L', 230-30, 800+20, 50, 15],
				['M_R', 230+30, 800+20, 50, 15]
			].do {|desc, elementnum|
				var element;
				element = StaticText.new(gui_win, Rect(desc[1], desc[2], desc[3], desc[4]));
				element.string_(desc[0]).stringColor_(Color.white).background_(Color.black).font_(labelfont);
				gui_pads.put(desc[0], element);
			};

			// Scopes
			gui_scopes = ();
			[
				//["RX1 AMP / TX1 / TX2", 3, SatSkin.bMONITOR_RX1, 10, 900, 250, 225],
				//["RX2 AMP / TX1 / TX2", 3, SatSkin.bMONITOR_RX2, 10 + 250, 900, 250, 225],
				["MIC L / R", 2, SatSkin.bMIC_L, 210, 0, 270, 225],
				["SPKR L / R", 2, SatSkin.bSPK_L, 210+270+10, 0, 270, 225],
				["SUB", 1, SatSkin.bSUB, 210+270+270+10, 0, 220, 225],
			].do {|desc, scopenum|
				var thescope, thebounds;
				thescope = Stethoscope.new(Server.default, desc[1], desc[2], view: gui_win);
				thebounds = thescope.view.bounds;
				thebounds.left = desc[3]; thebounds.top = desc[4];
				thebounds.width = desc[5]; thebounds.height = desc[6];
				thescope.view.bounds = thebounds;
				gui_scopes.put(desc[0], thescope);
				StaticText.new(gui_win,
					Rect(desc[3], desc[4] + 20, 80, 30)
				).string_(desc[0]).font_(labelfont).align_(\left).stringColor_(labelcolor);
			};

			// Frequency Scopes - reflect what is output on the speakers!
			gui_freqScopes = ();
			[["RX1 Hz", SatSkin.bRX1, 10, 240, 600, 150],
				["RX2 Hz", SatSkin.bRX2, 10, 240 + 150 + 10, 600, 150]].do {|desc, scopenum|
				var thescope;
				thescope = FreqScopeView(gui_win, Rect(desc[2], desc[3], desc[4], desc[5]));
				thescope.inBus_(desc[1]); // bus to listen to!
				thescope.active_(false); // turn it off, in case the server isn't running
				gui_freqScopes.put(desc[0], thescope);
				StaticText.new(gui_win, Rect(desc[2], desc[3], 70, 30)).string_(desc[0]).font_(labelfont).align_(\left).stringColor_(labelcolor);
			};

			// Number boxes
			gui_numberboxes = ();
			[
				['RX1_TX1', 1.0, 620, 240, 60, 30],
				['RX1_TX2', 1.0, 700, 240, 60, 30],
				['RX2_TX1', 1.0, 620, 280, 60, 30],
				['RX2_TX2', 1.0, 700, 280, 60, 30],
			].do {|desc, boxnum|
				var thebox, thestring;
				thestring = desc[0].asString + $\n + desc[1].asString;
				thebox = StaticText.new(gui_win, Rect(desc[2], desc[3], desc[4], desc[5]))
				.string_(thestring).font_(labelfont).align_(\left)
				.stringColor_(labelcolor).background_(labelbgcolor);
				gui_numberboxes.put(desc[0], thebox);
			};

			// Faders!
			gui_faders = ();
			[
				['MASTER', 1.0, 620, 320, 50, 200, SatSkin.dMASTERGAIN, 0.0, 1.0],
				['SUB_GAIN', SatSkin.sub_gain, 620 + 55, 320, 50, 200, SatSkin.dSUB_GAIN, 0.0, 1.0],
				['SUB_CO_HZ', SatSkin.sub_cutoff, 620+55+55, 320, 50, 200, SatSkin.dSUB_CUTOFF, 20, 130],
				['RX1', SatSkin.rx1_pregain, 620, 320+230, 50, 200, SatSkin.dRX1_PREGAIN, 0.0, 10.0],
				['RX2', SatSkin.rx2_pregain, 620+55, 320+230, 50, 200, SatSkin.dRX2_PREGAIN, 0.0, 10.0],
			].do {|desc, fadernum|
				var thefader, thenumber, thestring, theval;
				thestring = desc[0].asString + $\n + desc[1].asString;
				thenumber = StaticText.new(gui_win, Rect(desc[2], desc[3] + desc[5], desc[4], 30))
				.string_(thestring).font_(labelfont).align_(\left)
				.stringColor_(labelcolor).background_(labelbgcolor);
				gui_numberboxes.put(desc[0], thenumber);
				JUtil.debug(desc);
				thefader = Slider.new(gui_win, Rect(desc[2], desc[3], desc[4], desc[5]))
				.orientation_(\vertical);
				thefader.action_({|sld|
					var val, str;
					val = sld.value.trunc(0.0001);
					val = val.linlin(0.0, 1.0, desc[7], desc[8]);
					desc[6].value_(val);
					str = desc[0].asString + $\n + val;
					gui_numberboxes[desc[0]].string_(str);
				});
				desc[6].value_(desc[1]);
				thefader.value = desc[1].linlin(desc[7], desc[8], 0.0, 1.0);
				gui_faders.put(desc[0], thefader);
			};

			// Input/Output Meters
			gui_meter = ServerMeterView.new(satskin.server, gui_win, 0@0, 4, 4);
		};
		gui_win.front;
		gui_win.onClose = {
			// You must have this for all FreqScopeViews
			gui_freqScopes.do {|thescope, itemnum|
				thescope.kill; thescope.kill;
			};
			this.cleanup;
		};
	}


		freqScopesActive {|active=true|
			if(gui_freqScopes.notNil) {
				gui_freqScopes.do {|thescope, itemnum|
					thescope.active_(active);
				};
			};
		}

		setNumberBox {|boxsymbol, value|
			var thestring = (boxsymbol.asString + $\n + value.asString);
			{
				gui_numberboxes[boxsymbol].string_(thestring);
			}.fork(AppClock);
		}


		cleanup {
			synth_monitor.clear;
			osc_from_server.free;
		//JUtil.debug("Cleanup satskin gui");
			if(gui_win.notNil && gui_win.isClosed.not) {
				gui_win.close;
			};
		}


	}