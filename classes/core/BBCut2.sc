//This file is part of The BBCut Library. Copyright (C) 2001  Nick M.Collins distributed under the terms of the GNU General Public License full notice in file BBCutLibrary.help

//BBCut2 24/12/04  by N.M.Collins
//scheduling system corrections 12/05/05
//added delay classvar 13/11/05, to allow easy adjustment to sync with old style scheduling (but defaults to 0.0 for no effect)


//can derive other versions by overriding provideMaterial, say if want a kick/snare drummer etc

BBCut2 {
    classvar <>delay=0.0;
    var <cutgroups, <proc, <clock, <quantiser;
    var cache; //material temporarily held here
    var upto;
    var <>playflag;
    var <alive; //special flag set to false once killed, checked by scheduler

    //should pass in tempoclock, server
    *new {arg cutgroups, proc, quantiser;
        ^super.new.initBBCut2(cutgroups, proc, quantiser);
    }

    initBBCut2 {arg cg, p, q;
        var clk;

        Server.default.serverRunning.not.if {
            Error("Server must be booted before creating BBCut2.").throw;
        };

        cutgroups = cg.isKindOf(Array).if {
            cg[0].isKindOf(Array).if {
                // cg is an array of arrays -- make a CutGroup out of each one.
                cg.collect(CutGroup(_));
            } {
                cg[0].isKindOf(CutSynth).if {
                    // cg is an array of CutSynths -- make a CutGroup out of them.
                    CutGroup(cg);
                } {
                    // Dunno.
                    cg;
                };
            };
        } {
            // if nil
            cg = cg ?? { CutGroup(CutSynth()) };

            cg.isKindOf(CutSynth).if {
                CutGroup(cg);
            } {
                cg;
            };
        };

        proc = p ?? { BBCutProc11() };

        //
        //		clock=c ?? {
        //		clk= ExternalClock.default; //needs to be ExternalClock.new;
        //		//clk.play;
        //		clk
        //		}; //should pass in a clock
        //

        quantiser = q; // may be nil if no quantising to do

        //scheduling queue preparation
        upto = 0.0;
        cache = LinkedList();   //could use a PriorityQueue but cheaper to avoid any searches of position

        playflag = true;

        //ready, so add myself to the clock
        //clock.addslave(this);
    }

    //start playing on given clock
    play {arg clk;

        //if bps passed in
        if(clk.isKindOf(SimpleNumber),{

            clk= ExternalClock(TempoClock(clk)).play;
        });

        //recovery if TempoClock passed in
        if(clk.isKindOf(TempoClock),{

            clk= ExternalClock(clk).play;
        });

        if(not(clk.isKindOf(ExternalClock)),{

            //"default clock made".postln;

            clk= ExternalClock(TempoClock.default).play;
        });

        //clk.postln;

        if(clock.isNil, {clock=clk;
            //ready, so add myself to the clock
            alive= clock.addprovider(this);
        });

    }

    //should also pause any synthesis in Groups? No, because will recommence later?
    pause {
        playflag= false;
        //set a pause flag so don't bother to render material, still ask for blocks though to keep process ticking over in sync
    }

    resume {
        playflag=true;
    }

    clear {
        this.free;
    }

    //Julian uses this in NodeProxy
    end {
        this.stop;
        this.free;
    }

    //free resources, ie Busses, Nodes
    free {

        //now free all rendering synths- also flag that cannot return any material for safety
        cutgroups.do({arg val; val.free});

    }

    //stop playing on clock
    stop {

        alive.value=false; //set Reference flag to false here.
        clock=nil;

        //will be removed from the clock automatically
        //clock.removeslave(this);

    }

    //find CutMixers via CutGroups and sets main volumes to the value given
    amp_ {arg amp=0.1;
        cutgroups.do({arg val; val.amp_(amp)});

    }

    //find CutMixers via CutGroups and sets panfuncs to the value given
    pan_ {arg pan=0.0;
        cutgroups.do({arg val; val.pan_(pan)});
    }

    //substitute a new cut procedure-
    //will provide material from the next beat
    proc_ {arg p;

        //other killed by garbage collector
        proc=p ?? {BBCutProc11.new};
        
        //will be initial lateness in first beat since should have scheduled at latency already
        upto=0.0;
        cache=LinkedList.new;

    }


    //need is amount in beats, corrected takes account of latency with respect to current tempo, assumption that s.latency*bps< 0.5
    //ie 2 lots of latency within minimum period

    provideMaterial {arg beatlocation, need=1.0, tempo;
        var want,have,fetched,block;
        var removed, toschedule, tokeep;
        var endsec;
        var iterate; //condition for while loop
        var event;
        var scheduletime;
        var server;
        var latency;

        endsec= need/tempo;

        have= upto-need; //(beatlocation+need);

        want=0.5-have;

        //[\provideMaterial, beatlocation, upto, have].postln;

        if (have<0.5, //assume you have the data already if greater than
            {
                //get a block at a time, keep asking for material if don't have enough, render as you go

                fetched=0.0;

                //keep getting blocks until have enough data to schedule

                server= if(cutgroups.size>0,{cutgroups[0].server},{Server.default});//must be common Server over CutGroups

                while({fetched<want},{

                    block=this.getBlock;

                    //work out all server msgs at this point for any controlled BBCutGroups and synths
                    //renderer could be a single bbcut thing, would work if didn't need group info

                    //to time stamp all events
                    block.startbeat=beatlocation+upto+fetched; //fetched+upto;

                    //as long as something here!
                    if(block.length>0.001,{

                        cutgroups.do({arg val; val.renderBlock(block,clock)});

                        fetched=fetched+(block.length);

                        //Post << block.cuts << nl;

                        //should be a BBCutBlock method
                        cache=block.cacheMsgs(cache, server);

                    });

                    //Post << "cache test " << cache << nl;

                });

                upto=upto+fetched;

        });


        //default event in case you need to wait
        toschedule=List[[0,nil]];

        if(cache.notEmpty,{

            tokeep=LinkedList.new;

            //pop one at a time, passing needed messages toschedule, else tokeep. If get sufficiently far from needed time, assume all
            //further events don't need to be scheduled yet. Very long PATs or Server latencies would mess this up

            iterate=true;

            while({iterate},{

                event= cache.popFirst;

                if(event.isNil, {iterate=false;},
                    {

                        latency= (event[2][\server] ?? {Server.default}).latency;

                        //(clock.s.latency)
                        scheduletime= (((event[0])-beatlocation)/tempo) + (event[1])- latency+(BBCut2.delay);

                        if(	scheduletime< endsec, {

                            //add toschedule

                            //Post << [\scheduletime, scheduletime, \bool, (scheduletime>=(-0.001)), \playflag, playflag,playflag  && (scheduletime>=(-0.001)), endsec, event[0], event[1], event[2]] << nl;

                            //if(scheduletime<0.0, {scheduletime=0.0}); //negative times can't be caught, so make asap

                            //	if(playflag  && (scheduletime>=(-0.001)),{
                            //
                            //					"urk!".postln;
                            //					});


                            //Post << [\scheduletime, scheduletime, \max, max(0, scheduletime)] << nl;

                            //&& (scheduletime>=(-0.001))
                            //only actually send to scheduler if not paused
                            if(playflag && (scheduletime>=(-0.0001)),{

                                scheduletime = max(0, scheduletime); //negative times can't be caught, so make asap

                                toschedule.add([scheduletime, event[2]]);
                            });

                        }, {

                            tokeep.add(event);

                            //finished, don't schedule any of remainder
                            //allows 0.5 sec leeway for PAT, expressive timing deviations
                            if (scheduletime> (endsec+0.5), {
                                iterate=false;
                            });

                        });

                });


            });

            cache= tokeep++cache;

            //sort toschedule into order, and prepare as deltas for sequential SystemClock scheduling

            toschedule= toschedule.sort({arg a,b; a[0]<b[0]});
            //into delta times

            toschedule.do({arg val,i;

                if(i<(toschedule.size-1),{

                    val[0]=(toschedule[i+1][0])-(val[0]);
                },{

                    //last ioi must be nil to kill sched
                    val[0]=nil;
                });

            });

            //every so often scale locations in cache back to range 0.0 to 100.0 beats?
        });
        //
        //	,{
        //
        //	//could happen if tempo change, already sent all messages for upcoming block...
        //	"this also shouldn't happen unless no cut synths at all, cache is empty".postln;
        //
        //	}


        //Post << "schedule test " << toschedule << nl;

        upto=upto-need;

        //toschedule will contain a mixture of msg Lists and individual msgs?
        ^toschedule;
    }


    getBlock {
        // Retrieve block from cut procedure
        var block = proc.next;

        // quantise must occur here, must adjust b.length too
        quantiser.notNil.if { quantiser.value(block) };

        // used to be clock.tempoclock.tempo
        // so ioi in beats but dur is in seconds, needed for rendering - is it?
        block.scaleDurations(clock.tempo.reciprocal);

        // Set properties: msgs, iois, cumul
        block.update;

        ^block;
    }

}