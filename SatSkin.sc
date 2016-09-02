/*

Abstraction for Satellite-Skin based feedback & sensing systems.


Usage:

l = SatSkin.new();

// Create the TXRX ugens for 2 TX starting at bus 2 and 2 RX starting at bus 0
l.createTXRX(2, 0)

// Monitor the incoming signal strengths.
l.makegui();

// Set the responder action
l.rx_action = {|r1_1, r1_2, r2_1, r2_2|
var map1_1, map1_2, map2_1, map2_2;
map1_1 = rx1_1.linlin(0.0, 0.5, 0.0, 1.0);
map1_2 = rx1_2.linlin(0.0, 0.5, 0.0, 1.0);
map2_1 = rx2_1.linlin(0.0, 0.5, 0.0, 1.0);
map2_2 = rx2_2.linlin(0.0, 0.5, 0.0, 1.0);
Post << "MAPPED: " << [map1_1, map1_2, map2_1, map2_2] << Char.nl;
};

// Change signal detection frequencies
l.f1 = 27000;
l.f2 = 18000;

// Post incoming signal strengths
l.post_rx = true;

*/

// Some useful pseudoUGens?
// A compressor


/**** SOUNDCARD SETUP ****
Set numoutputs, numinputs to correct values.
Set up bus number constants
Make sure the sound card's sample rate matches the sample rate in SatSkin
****/
SatSkin {
	// Constants
	classvar <soundcard = "EDIROL FA-101 (3797)";
	classvar <sampleRate = 88200;
	const <blocksize = 64;

	// Audio Busses
	classvar <nOUTPUTS = 6;
	classvar <bSPK_L = 0;
	classvar <bSPK_R = 1;
	classvar <bTX1 = 2;
	classvar <bTX2 = 3;
	classvar <bSUB = 5;

	classvar <nINPUTS = 4;
	classvar <bMIC_L = 6;
	classvar <bMIC_R = 7;
	classvar <bRX1 = 8;
	classvar <bRX2 = 9;

	// Data Busses (created after booting)
	classvar <dRX1 = nil; // 2-channel, TX1 and TX2 signal strength
	classvar <dRX2 = nil;

	classvar <dRX1_TX1 = nil;
	classvar <dRX1_TX2 = nil;
	classvar <dRX2_TX1 = nil;
	classvar <dRX2_TX2 = nil;

	classvar <dMASTERGAIN = nil;
	classvar <dSUB_GAIN = nil;
	classvar <dSUB_CUTOFF = nil;
	classvar <dRX1_PREGAIN = nil;
	classvar <dRX2_PREGAIN = nil;

	// Sub settings
	classvar <sub_cutoff = 100;
	classvar <sub_gain = 0.2;
	classvar <rx1_pregain = 1.0;
	classvar <rx2_pregain = 1.0;

	// TX frequencies
	classvar <tx1_hz = 26297.9;
	classvar <tx2_hz = 27852.4;
	classvar <analysis_freqs;

	// Class variables
	classvar <win_meter, <win_scope;
	classvar <synth_emf_scan;

	var <prox_sense_enabled; // if true, when everything is initialized will also send prox sense signals
	var <gui; // the gui
	var <prox; // the proximity/touch signal processor
	var <syn_sub; // sub synth
	var <server; // server

	*new {|serv=nil|
		^super.new.init(serv);
 	}

	init {|serv|
		if(serv.isNil) { serv = Server.default; };
		server = serv;
	}

	// Called at language startup
	*initClass {
		// Analysis frequencies
	// NTS:: !!! Keep in mind the AC properties of the audio trafos - they tend to let through more of
	// lower frequencies approaching 10khz. So higher frequencies need to be compensated
	// accordingly. !!!

		analysis_freqs = [16622, 17972, 18972, 20122, 21772, 22222];
	}


	setupProxSense {
 		{
			prox = SatSkinProxSense.new(this);
			server.sync;
			prox.setupTX(1, SatSkin.bTX1, SatSkin.tx1_hz, 1.0);
			prox.setupTX(2, SatSkin.bTX2, SatSkin.tx2_hz, 1.0);
			prox.setupRX(1, SatSkin.bRX1, SatSkin.tx1_hz, SatSkin.tx2_hz, SatSkin.dRX1_PREGAIN, SatSkin.dRX1);
			prox.setupRX(2, SatSkin.bRX2, SatSkin.tx1_hz, SatSkin.tx2_hz, SatSkin.dRX2_PREGAIN, SatSkin.dRX2);
			server.sync;
		}.fork;
	}

	makeGui {|xpos=700, ypos=0|
		if(gui.notNil) {
			gui.cleanup();
		};
		gui = SatSkinGui.new(this);
		gui.setupGui(xpos, ypos);
		JUtil.debug("End of SatSkin.makeGui success!");
	}

	makeSub {|subgain=0.4, sub_co_hz=70|
		if(syn_sub.notNil) { syn_sub.free; };
		sub_gain = subgain;
		sub_cutoff = sub_co_hz;
		SatSkin.dSUB_CUTOFF.value_(sub_cutoff);
		SatSkin.dSUB_GAIN.value_(sub_gain);
		syn_sub = {|spkL_mix=0.2, spkR_mix=0.2|
			var sig_sub, sig_l, sig_r, gain, cutoff_hz;
			gain = SatSkin.dSUB_GAIN.kr;
			cutoff_hz = SatSkin.dSUB_CUTOFF.kr;
			sig_l = InFeedback.ar(SatSkin.bSPK_L, 1);
			sig_r = InFeedback.ar(SatSkin.bSPK_R, 1);
			sig_sub = BLowPass4.ar(sig_l, cutoff_hz, mul: spkL_mix) + BLowPass4.ar(sig_r, cutoff_hz, mul: spkR_mix);
			Out.ar(SatSkin.bSUB, sig_sub * gain);
		}.play(server);
	}


	// CLEANUP ALL THE SYNTHS & OBJECTS
	cleanup {
		synth_emf_scan.free;
			syn_sub.free;
		gui.cleanup();
		prox.cleanup();
	}




	/**** UTILITY FUNCTIONS ****/

	// SIMPLE CHANNEL MONITORS & MULTICHANNEL SCOPE
	*monitor {|xpos=800, ypos=0, server=nil|
		if(server.isNil) { server = Server.default; };
		// Monitor Outputs from insig
		win_scope = server.scope(8, 0);
		win_meter = server.meter;
		win_scope.window.setTopLeftBounds(Rect(xpos, ypos, 500, 900), 10).front;
		win_meter.window.setTopLeftBounds(Rect(xpos + 300, ypos, 200, 300)).front;
		win_scope.window.addToOnClose({ win_scope = nil; });
		win_meter.window.addToOnClose({ win_meter = nil; });
		win_meter.window.alwaysOnTop = true;
	}

	// Boot the standard 2x2 setup
	*boot {|bootfunc=nil, device="EDIROL FA-101 (3797)"|
		var s = Server.local;
		Server.default = s;
		s.options.device = device;
		s.options.numOutputBusChannels = SatSkin.nOUTPUTS; // 2 main out to spkr, 2 tx -> out to DI/platform
		s.options.numInputBusChannels = SatSkin.nINPUTS;  // 2 mic in, 2 rx <- in from DI/platform
		s.options.blockSize = SatSkin.blocksize; // increase block size (default is 64)
		s.options.sampleRate = SatSkin.sampleRate;
		s.options.memSize = 8192 * 2;
		if(bootfunc.isNil) {
			s.boot;
		} {
			s.waitForBoot({
				SatSkin.initDataBuses;
				bootfunc.value;
			});
		};
	}

	// Make the data busses
	*initDataBuses {|server=nil|
		if(server.isNil) { server = Server.default };
		if(dRX1.isNil) { dRX1 = Bus.control(server, 4); };
		if(dRX2.isNil) { dRX2 = Bus.control(server, 4); };


		if(dRX1_TX1.isNil) { dRX1_TX1 = Bus.control(server, 1); };
		if(dRX1_TX1.isNil) { dRX1_TX2 = Bus.control(server, 1); };
		if(dRX1_TX1.isNil) { dRX2_TX1 = Bus.control(server, 1); };
		if(dRX1_TX1.isNil) { dRX2_TX2 = Bus.control(server, 1); };

		if(dMASTERGAIN.isNil) { dMASTERGAIN = Bus.control(server, 1); };
		dMASTERGAIN.value = 1.0;
		if(dSUB_GAIN.isNil) { dSUB_GAIN = Bus.control(server, 1); };
		dSUB_GAIN.value = 0.2;
		if(dSUB_CUTOFF.isNil) { dSUB_CUTOFF = Bus.control(server, 1); };
		dSUB_CUTOFF.value = 100;
		if(dRX1_PREGAIN.isNil) { dRX1_PREGAIN = Bus.control(server, 1); };
		dRX1_PREGAIN.value = 1.0;
		if(dRX2_PREGAIN.isNil) { dRX2_PREGAIN = Bus.control(server, 1); };
		dRX2_PREGAIN.value = 1.0;
 	}


	// NTS:: TODO:: This doesn't quite work yet.
	// Scan room for ambient EM frequencies. active=false stops scanning, use mouse to scan through frequencies
	*fscan {|active=true, server=nil, rcvbus=4|
		// Monitoring Room Noise
		// View Room Noise on input 1 & 2 of the soundcard.
		// TODO: Implement an autocalibration algorithm for finding the lowest present frequency in the space
		if(server.isNil) { server = Server.default; };
		if(active) {
			if (synth_emf_scan.notNil) { synth_emf_scan.free; synth_emf_scan = nil; };
			synth_emf_scan = {|f1, f2|
				var in1, in2, sig, rms, amp;
				f1 = MouseY.kr(40, 46000, 4).poll; // Scan through
				in1 = InFeedback.ar(rcvbus);
				in2 = InFeedback.ar(rcvbus + 1);
				sig = [in1, in2];
				sig = BPF.ar(sig, f1, 0.05);
				Out.ar(10, sig);
				amp = Amplitude.ar(sig[0], 0.001, 0.1) * 3.0;
				rms = RunningSum.rms(sig[0], 500) * 3.0;
				Out.ar(12, [amp, rms]);
			}.play(server);
			server.scope(4, 10);
			FreqScope.new(busNum: 10);
		} {
			synth_emf_scan.free; synth_emf_scan = nil;
		};
	}

	// *** Sound Tests & Experiments *** //

	// Direct connection of input mic to output speaker
	*testRawFeedback {|inbus=0, outbus=0, ingain=1.0, outgain=1.0, timeout=10.0|
		^{
			var sig, amp;
			sig = In.ar(inbus) * ingain * EnvGen.ar(Env.linen(0.1, timeout, 0.1), doneAction: 2);
			sig = Compander.ar(sig, sig, 0.8, 1.0, 1/2, 0.002, 0.01);
			sig = LeakDC.ar((sig * outgain));
			Out.ar(outbus, sig);
		}.play;
	}

	// Connection of input mic to output speaker with additional PM Oscillator sweep mixed in to excite frequencies.
	*testExcitedFeedback {|inbus=0, outbus=0, ingain=1.0, outgain=1.0, f_start=10000, f_end=10100, dur=1.0|
		^{
			var sig, amp, env;
			env = EnvGen.ar(Env.sine(dur), doneAction: 2);
			amp = Lag.ar(Crackle.ar(1.5).range(0, 1), 0.05);
			sig = PMOsc.ar(Line.ar(f_start, f_end, dur), Line.ar(50, 10, dur), Line.ar(1.01, 1.1, dur), Line.ar(0.0, 1.0, dur));
			sig = sig + (In.ar(inbus) * ingain);
			sig = LeakDC.ar(sig);
			Out.ar(outbus, sig * amp * env * outgain);
		}.play;
	}

	//*** Play a sine tone every time the input level goes above thresh ***//
	*testInputSignal {|inbus=0, outbus=0, thresh=0.1, pregain=1.0, gain=1.0, timeout=1200|
		^{
			var xin, sig, amp, t_trig, timer;
			xin = In.ar(inbus) * pregain;
			amp = Amplitude.ar(xin);
			t_trig = amp > thresh;
			timer = Line.kr(0, 1000, timeout, doneAction: 2);
			sig = SinOsc.ar(62.midicps, mul: 0.5) * EnvGen.ar(Env.sine(1), gate: t_trig, doneAction: 0);
			Out.ar(outbus, sig * gain);
	}.play;
	}

	//*** Play a complex waveform / sweep at a given output ***//
	*testOutputSignal {|outbus=0, gain=1.0, timeout=1200|
		^{
			var sig, timer, env;
			timer = Line.kr(0.0, 1.0, timeout, doneAction: 2);
			env = EnvGen.ar(Env.linen(0.1, timeout, 0.1));
			sig = SinOsc.ar(440 + SinOsc.ar(timer * 2000).range(0.01, 300));
			Out.ar(outbus, sig * env * gain);
	}.play;
	}

	// *** TX RX Tests & Experiments *** //

	// Basic single loop touch feedback:
	// Connect a tx channel to a mic input and an rx channel to a speaker output
	*testTXRXFeedback {|txbus, inbus, rxbus, outbus, txboost=1.0, outgain=1.0, timeout=1200|
		^{
			var sig, timer, env;
			timer = Line.kr(0.0, 1.0, timeout, doneAction: 2);
			env = EnvGen.ar(Env.linen(0.1, timeout, 0.1));
			sig = In.ar(inbus);
			Out.ar(txbus, sig * txboost);

			sig = In.ar(rxbus);
			sig = LeakDC.ar(sig);
			sig = Compander.ar(sig, sig, 0.8, 1.0, 1/2, 0.002, 0.01);
			Out.ar(outbus, sig * env * outgain);
	}.play;
	}



}