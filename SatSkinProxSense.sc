/****

Satellite Skin
Touch-Proximity Detection

****/

// Makes an RX signal suitable to use as a control signal.
// Gets an average summation value..
// Returns: [filtered signal, amplitude signal, rms signal]
RX_DataFilter : UGen {
	*ar {|sig, filter_freq, filter_rq=0.1, cmpthresh=10, cmpslope_lo=5, cmpslope_hi=1, cmpatk=0.002,
		sumsamps=3000, lag=0.01|
		var filt, amp, rms, out;
		filt = BLowPass4.ar(BHiPass4.ar(sig, filter_freq, filter_rq), filter_freq, filter_rq);
		filt = Compander.ar(filt, filt, cmpthresh, cmpslope_lo, cmpslope_hi, cmpatk);
		amp = Amplitude.ar(filt,0.001,0.001);
		rms = RunningSum.rms(filt, sumsamps);
		rms = Lag3.ar(rms, lag);
		out = [filt / 10, amp, rms];
		^out;
	}
}


SatSkinProxSense {
	var satskin;
	var <syn_tx, <syn_rx;
	var <rx_callback; // callback func for incoming rx values. Takes 4 arguments, one for each RX-TX permutation
	var <oscdef_listener;
	var routine_polling;
	var <speakProx_r; // speakprox routine
	var <speakProx_rxid, <speakProx_txid;


	*new {|thesatskin|
		^super.new.init(thesatskin);
	}

	init {|thesatskin|
		satskin = thesatskin;
		// SET UP RESPONDER FUNCTION
		rx_callback = {|r1_1, r1_2, r2_1, r2_2|
			// Do nothing.
		};

		syn_tx = [nil, nil];
		syn_rx = [nil, nil];

		this.initSynthdefs;
	}

	initSynthdefs {
		SynthDef(\prox_tx, {|txbus, tx_hz=26297.9, tx_boost=1.0|
			var tx;
			tx = SinOsc.ar(tx_hz);
			Out.ar(txbus, tx * tx_boost);
		}).add;

		SynthDef(\prox_rx, {|rxbus, tx1_hz, tx2_hz, tx1_rq=0.1, tx2_rq=0.1,
			rx_boost=1.0, data_rate=40, pregainbus, databus, tx1_floor=0.001, tx2_floor=0.001|
			var rx_raw, rx_raw_amp, rx_filtered;
			var tx1, tx2, tx1_amp, tx2_amp, tx1_rms, tx2_rms;
			var t_trig;
			var monitor_sig;
			var pregain = In.kr(pregainbus, 1);
			t_trig = Impulse.kr(data_rate);

			rx_raw = InFeedback.ar(rxbus) * pregain;

			// Get general audio amplitude
			rx_raw_amp = Amplitude.ar(BLowPass4.ar(rx_raw, 15000, 1.0), 0.001, 0.001);

			// Signals filtered to receive specific TX frequencies
			tx1 = RX_DataFilter.ar(rx_raw, tx1_hz, tx1_rq);
			tx2 = RX_DataFilter.ar(rx_raw, tx2_hz, tx2_rq);

			// Map the signals to an inverse exponential scale to emphasize the smaller values
			// and attenuate the larger ones
			tx1_amp = A2K.kr(tx1[1]).explin(tx1_floor, 1.0, 0.0, 1.0, \min);
			tx2_amp = A2K.kr(tx2[1]).explin(tx2_floor, 1.0, 0.0, 1.0, \min);
			tx1_rms = A2K.kr(tx1[2]).explin(tx1_floor, 1.0, 0.0, 1.0, \min);
			tx2_rms = A2K.kr(tx2[2]).explin(tx2_floor, 1.0, 0.0, 1.0, \min);

			Out.kr(databus, [tx1_amp, tx2_amp, tx1_rms, tx2_rms]);
		}).add;
	}

	// Set up a transmitter
	setupTX {|txnum, txbus, tx_hz, tx_boost|
		var txid=txnum-1;
		syn_tx[txid] = Synth(\prox_tx, [\txbus, txbus, \tx_hz, tx_hz ,\tx_boost, tx_boost]);
	}

	setTX {|txnum, tx_hz, tx_boost|
		var txid = txnum-1;
		syn_tx[txid].set(\tx_hz, tx_hz, \tx_boost, tx_boost);
	}

	// Set up one receiver
	setupRX {|rxnum, rxbus, tx1_hz, tx2_hz, cbus_pregain, cbus_out|
		var rxid = rxnum-1;
		syn_rx[rxid] = Synth(\prox_rx, [\rxbus, rxbus, \tx1_hz, tx1_hz, \tx2_hz, tx2_hz, \tx1_rq, 0.1, \tx2_rq, 0.1, \rx_boost, 1.0, \data_rate, 60, \pregainbus, cbus_pregain, \databus, cbus_out]);
	}

	setRX {|rxnum, tx1_hz=nil, tx2_hz=nil, tx1_rq=nil, tx2_rq=nil, rx_boost=nil, data_rate=nil, databus=nil, tx1_floor=nil, tx2_floor=nil|
		var argarr, rxid = rxnum-1;
		argarr = [];
		if(tx1_hz.notNil) { argarr = argarr.add(\tx1_hz); argarr = argarr.add(tx1_hz); };
		if(tx2_hz.notNil) { argarr = argarr.add(\tx2_hz); argarr = argarr.add(tx2_hz); };
		if(tx1_rq.notNil) { argarr = argarr.add(\tx1_rq); argarr = argarr.add(tx1_rq); };
		if(tx2_rq.notNil) { argarr = argarr.add(\tx2_rq); argarr = argarr.add(tx2_rq); };
		if(rx_boost.notNil) { argarr = argarr.add(\rx_boost); argarr = argarr.add(rx_boost); };
		if(data_rate.notNil) { argarr = argarr.add(\data_rate); argarr = argarr.add(data_rate); };
		if(databus.notNil) { argarr = argarr.add(\databus); argarr = argarr.add(databus); };
		if(tx1_floor.notNil) { argarr = argarr.add(\tx1_floor); argarr = argarr.add(tx1_floor); };
		if(tx2_floor.notNil) { argarr = argarr.add(\tx2_floor); argarr = argarr.add(tx2_floor); };
		syn_rx[rxid].set(*argarr);
	}

	// rxid 1, 2 (not 0 indexed)
	speakProx {|active=true, rxnum=nil, txnum=nil|
		if(active != true) {
			if(speakProx_r.notNil) {
				speakProx_r.stop;
			};
		} {
			if(rxnum.notNil) {
				speakProx_rxid = rxnum - 1;
			};
			if(txnum.notNil) {
				speakProx_txid = txnum - 1;
			};
			if(speakProx_r.isNil) {
				this.initSpeakProx();
			};
			speakProx_r.play;
		};
	}

	initSpeakProx {
		speakProx_r = Tdef(\spoken_feedback, {
			var rx, rx1, rx2, str, val;
			inf.do {|i|
				// Get latest data from the bus
				rx1 = SatSkin.dRX1.getnSynchronous(4);
				rx2 = SatSkin.dRX2.getnSynchronous(4);
				rx = [rx1, rx2];

				val = rx[speakProx_rxid][speakProx_txid].trunc(0.001);
				str = "RX"+(speakProx_rxid+1)+" TX"+(speakProx_txid+1)+": "+val;
				speak(str);

				Post << "RX1_TX1: " << rx[0][0].trunc(0.001) << "  RX1_TX2: " << rx[0][1].trunc(0.001)
				<< "  RX2_TX1: " << rx[1][0].trunc(0.001)
				<< "  RX2_TX2: " << rx[1][1].trunc(0.001) << $\n;
				6.0.wait;
			};
		});
	}


	cleanup {
		syn_tx.do {|syn|
			syn.free;
		};
		syn_rx.do {|syn|
			syn.free;
		};
		oscdef_listener.free;
	}
}

