package com.ndnhealthnet.androidudpclient.Comm;

import android.content.Context;

import com.ndnhealthnet.androidudpclient.Activities.MainActivity;
import com.ndnhealthnet.androidudpclient.DB.DBDataTypes.CSEntry;
import com.ndnhealthnet.androidudpclient.DB.DBDataTypes.FIBEntry;
import com.ndnhealthnet.androidudpclient.DB.DBDataTypes.PITEntry;
import com.ndnhealthnet.androidudpclient.DB.DBSingleton;
import com.ndnhealthnet.androidudpclient.Utility.ConstVar;
import com.ndnhealthnet.androidudpclient.Utility.JNDNUtils;
import com.ndnhealthnet.androidudpclient.Utility.Utils;

import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Class handles incoming UDP packets.
 */
public class UDPListener extends Thread {

    static  DatagramSocket clientSocket = null;
    static Context context;

    public UDPListener(Context context) {
        this.context = context;
    }

    @Override
    public void run() {
        try {
            InetSocketAddress address = new InetSocketAddress(MainActivity.deviceIP, ConstVar.PHINET_PORT);

            clientSocket = new DatagramSocket(null);
            clientSocket.bind(address); // give receiver static address

            byte[] receiveData = new byte[1024];

            while (MainActivity.continueReceiverExecution) { // loop for packets while true

                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                try {
                    clientSocket.receive(receivePacket);

                    String hostIP = receivePacket.getAddress().getHostAddress();
                    int hostPort = receivePacket.getPort();

                    handleNDNPacket(receiveData, hostIP, hostPort);

                } catch (SocketTimeoutException e) {
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (clientSocket != null) {
                clientSocket.close();
            }
        }
    }

    /**
     * Helper method that handles all incoming packets;
     * may be invoked from elsewhere in code (namely, UDPSocket)
     *
     * @param packet received
     * @param hostIP of sender
     * @param hostPort of sender
     */
    static void handleNDNPacket(byte[] packet, String hostIP, int hostPort) {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        Interest interest = JNDNUtils.decodeInterest(buf);
        Data data = JNDNUtils.decodeData(buf);

        // either interest or data is non-null (the other failed to be decoded)

        if (data == null && interest != null) {

            Utils.storeInterestPacket(context, interest); // store packet in database for further review

            handleInterestPacket(interest, hostIP, hostPort);
        } else if (data != null && interest == null) {

            Utils.storeDataPacket(context, data); // store data packet in database for further review

            handleDataPacket(data);
        } else {
            // unknown packet type; drop it
        }
    }

    /**
     * Initial method for handling Interest packets. It directs
     * packet, based upon its name, to a specific method.
     *
     * @param interest sent by entity
     * @param ipAddr of sender
     * @param port of sender
     */
    static void handleInterestPacket(Interest interest, String ipAddr, int port) {

        //decode the parsing characters "||"
        String [] nameComponent = interest.getName().toUri().replace("%7C%7C", "||").split("/");

        // information extracted from our name format:
        // "/ndn/userID/sensorID/timeString/processID"
        // the indexes used are position + 1 (+1 is due to string properties)
        String userID = nameComponent[2].trim();
        String sensorID = nameComponent[3].trim();
        String timeString = nameComponent[4].trim();
        String processID = nameComponent[5].trim();

        // now, use the processID to determine what should be done

        if (processID.equals(ConstVar.INTEREST_CACHE_DATA)) {

            handleInterestCacheRequest(userID, sensorID, timeString,
                    processID, ipAddr, port);
        } else if (processID.equals(ConstVar.LOGIN_CREDENTIAL_DATA)
                            || processID.equals(ConstVar.REGISTER_CREDENTIAL_DATA)) {

            // store the request in the PIT; should be checked within Login or Signup Activity shortly
            PITEntry pitEntry = new PITEntry(sensorID, processID, timeString, userID, ipAddr);
            DBSingleton.getInstance(context).getDB().addPITData(pitEntry);

        } else if (processID.equals(ConstVar.SYNCH_DATA_REQUEST)) {

            handleInterestSynchRequest(userID, timeString, port);
        } else {
            // unknown processID; drop packet
        }
    }

    /**
     * Performs NDN logic on packet that requests data
     *
     * @param userID userID associated with requested data from cache
     * @param sensorID sensorID associated with requested data from cache
     * @param timeString timeString associated with requested data from cache
     * @param processID processID associated with requested data from cache
     * @param packetIP ip of entity that requested data from cache
     */
    static void handleInterestCacheRequest(String userID, String sensorID, String timeString,
                            String processID, String packetIP, int port)
    {
        // first, check CONTENT STORE (cache)
        ArrayList<CSEntry> csDATA = DBSingleton.getInstance(context).getDB().getGeneralCSData(userID);

        if (csDATA != null) {

            String dataPayload = "";

            for (int i = 0; i < csDATA.size(); i++) {

                // only reply to interest with data that matches date && sensorID request
              if (Utils.isValidForTimeInterval(timeString, csDATA.get(i).getTimeString())) {

                  // TODO - perform better validation (such as checking sensor id)

                  // append all data to single string since all going to single source
                  dataPayload += csDATA.get(i).getDataPayload()+ ",";
                }
            }

            // if valid data was found, now send Data packet
            if (!dataPayload.equals("")) {

                Name packetName = JNDNUtils.createName(userID, sensorID, timeString, processID);
                Data data = JNDNUtils.createDataPacket(dataPayload, packetName);

                CSEntry cacheEntry = new CSEntry(sensorID, processID, timeString,
                        userID, dataPayload, ConstVar.DEFAULT_FRESHNESS_PERIOD);

                // place data into Cache that is used to Satisfy the Interest
                DBSingleton.getInstance(context).getDB().addCSData(cacheEntry);


                // reply to interest with DATA from cache
                new UDPSocket(port, packetIP, ConstVar.DATA_TYPE).execute(data.wireEncode().getImmutableArray());

                // add packet content to database for future review
                Utils.storeDataPacket(context, data);
            }
        }
        // second, since no data found in CS, check PIT to see if an Interest was already sent
        else {

            // no Interests have been sent for this data; do so now
            if (DBSingleton.getInstance(context).getDB().getGeneralPITData(userID) == null) {

                // add new request to PIT, then look into FIB before sending request
                PITEntry newPITEntry = new PITEntry(sensorID, processID, timeString, userID, packetIP);

                DBSingleton.getInstance(context).getDB().addPITData(newPITEntry);

                ArrayList<FIBEntry> allFIBData = DBSingleton.getInstance(context).getDB().getAllFIBData();

                if (allFIBData == null || allFIBData.size() == 0) {

                    // FIB is empty, user must reconfigure
                    throw new NullPointerException("Cannot send message; FIB is empty.");
                } else {

                    for (int i = 0; i < allFIBData.size(); i++) {

                        // don't send data to same node that requested; check first
                        if (!allFIBData.get(i).getIpAddr().equals(packetIP)
                                && !allFIBData.get(i).getIpAddr().equals(ConstVar.NULL_FIELD)) {

                            Name packetName = JNDNUtils.createName(userID, sensorID,
                                    timeString, processID);
                            Interest interest = JNDNUtils.createInterestPacket(packetName);


                            new UDPSocket(port, allFIBData.get(i).getIpAddr(), ConstVar.INTEREST_TYPE)
                                    .execute(interest.wireEncode().getImmutableArray()); // reply to interest with DATA from cache

                            // store sent packet in database for further review
                            Utils.storeInterestPacket(context, interest);
                        }
                    }
                }
            }
            // Interests have been sent for data, append request to PIT and wait
            else {

                // add new request to PIT and wait, request has already been sent
                PITEntry newPITEntry = new PITEntry(sensorID, processID, timeString, userID, packetIP);
                DBSingleton.getInstance(context).getDB().addPITData(newPITEntry);
            }
        }
    }

    /**
     * Invoked when server sends a synchronization request.
     *
     * @param userID of client targeted by server for synch
     * @param timeString of data requested
     * @param port of sender
     */
    static void handleInterestSynchRequest(String userID, String timeString, int port) {

        // TODO - delete initial Interest from PIT

        ArrayList<CSEntry> candidateData = DBSingleton.getInstance(context).getDB().getGeneralCSData(userID);

        ArrayList<CSEntry> validData = new ArrayList<>();

        for (int i = 0; i < candidateData.size(); i++) {
            // verify that data came from valid sensor and is within proper interval
            if (!candidateData.get(i).getSensorID().equals(ConstVar.NULL_FIELD)
                   &&  Utils.isValidForTimeInterval(timeString, candidateData.get(i).getTimeString())) {
                validData.add(candidateData.get(i));
            }
        }

        // Syntax: Sensor1--data1,time1;; ... ;;dataN,timeN:: ... ::SensorN--data1,time1;; ... ;;dataN,timeN
        String formattedData = Utils.formatSynchData(validData);

        Name packetName = JNDNUtils.createName(userID, ConstVar.NULL_FIELD,
                timeString, ConstVar.SYNCH_DATA_REQUEST);
        Data data = JNDNUtils.createDataPacket(formattedData, packetName);

        CSEntry cacheEntry = new CSEntry(ConstVar.NULL_FIELD, ConstVar.SYNCH_DATA_REQUEST, timeString,
                userID, formattedData, ConstVar.DEFAULT_FRESHNESS_PERIOD);

        // place Synch Data Packet into CS
        DBSingleton.getInstance(context).getDB().addCSData(cacheEntry);

        // reply to interest with DATA from cache
        new UDPSocket(port, ConstVar.SERVER_IP, ConstVar.DATA_TYPE) .execute(data.wireEncode().getImmutableArray());

        Utils.storeDataPacket(context, data); // add packet content to database for future review
    }

    /**
     * handles DATA packet as per NDN specification
     * Method parses packet then stores in cache if requested,
     * and sends out to satisfy any potential Interests.
     *
     * @param data newly received
     */
    static void handleDataPacket(Data data) {

        // decode the parsing characters "||" and break into components
        String [] nameComponent = data.getName().toUri().replace("%7C%7C", "||").split("/");
        String dataContents = data.getContent().toString();

        // information extracted from our name format:
        // "/ndn/userID/sensorID/timeString/processID"
        // the indexes used are position + 1 (+1 is due to string properties)
        String userID = nameComponent[2].trim();
        String sensorID = nameComponent[3].trim();
        String timeString = nameComponent[4].trim();
        String processID = nameComponent[5].trim();

        // first, determine who wants the data
        ArrayList<PITEntry> allValidPITEntries = DBSingleton.getInstance(context).getDB()
                .getGeneralPITData(userID);

        // data was requested; handle it now
        if (allValidPITEntries != null && allValidPITEntries.size() > 0) {

            // determine if data packet matches any requests (various criteria considered)
            boolean requestFoundWithinInterval = false;
            for (int i = 0; i < allValidPITEntries.size(); i++) {

                // must match time, processID, and userID
                if ((Utils.isValidForTimeInterval(allValidPITEntries.get(i).getTimeString(), timeString)
                        || allValidPITEntries.get(i).getTimeString().equals(timeString))
                        && allValidPITEntries.get(i).getProcessID().equals(processID)
                        && allValidPITEntries.get(i).getUserID().equals(userID)) {

                    requestFoundWithinInterval = true;
                    break;
                }
            }

            if (requestFoundWithinInterval) {
                if (processID.equals(ConstVar.DATA_CACHE)) {

                    handleCacheData(userID, sensorID, timeString, processID,
                            dataContents, allValidPITEntries);
                } else if (processID.equals(ConstVar.LOGIN_RESULT)
                        || processID.equals(ConstVar.REGISTER_RESULT)
                        || Utils.isAnalyticProcessID(processID)) {

                    // these ProcessIDs all result in storing data into the ContentStore
                    CSEntry dataPacket = new CSEntry(sensorID, processID,
                            timeString, userID, dataContents, ConstVar.DEFAULT_FRESHNESS_PERIOD);

                    DBSingleton.getInstance(context).getDB().addCSData(dataPacket);

                } else {
                    // unknown process id; drop packet
                }
            } else {
                // no PIT requests found; drop packet
            }
        }
    }

    /**
     * Method handles incoming Data
     *
     * @param userID userID associated with incoming Data packet
     * @param sensorID sensorID associated with incoming Data packet
     * @param timeString timeString associated with incoming Data packet
     * @param processID processID associated with incoming Data packet
     * @param dataPayload contents of incoming Data packet
     * @param allValidPITEntries ArrayList of all PIT entries requesting this data
     */
    static void handleCacheData(String userID,String  sensorID,String  timeString,
                         String  processID,String  dataPayload,
                         ArrayList<PITEntry> allValidPITEntries) {

        // data was requested; second, update cache with new packet
        CSEntry data = new CSEntry(sensorID, processID, timeString, userID, dataPayload,
                ConstVar.DEFAULT_FRESHNESS_PERIOD);

        // TODO - rework how update/addition takes place (currently, may not store if 3rd party requested)

        // if data exists in cache, just update
        if (DBSingleton.getInstance(context).getDB().getGeneralCSData(userID) != null) {

            DBSingleton.getInstance(context).getDB().updateCSData(data);
        } else {

            // data not in cache, add now
            DBSingleton.getInstance(context).getDB().addCSData(data);
        }

        // now, send packets to each entity that requested the data
        for (int i = 0; i < allValidPITEntries.size(); i++) {

            // data satisfies PIT entry; delete the entry
            DBSingleton.getInstance(context).getDB().deletePITEntry(allValidPITEntries.get(i).getUserID(),
                    allValidPITEntries.get(i).getTimeString(), allValidPITEntries.get(i).getIpAddr());

            // another device requested the data, send reply as Datapacket
            if (!allValidPITEntries.get(i).getIpAddr().equals(MainActivity.deviceIP))  {


                Name packetName = JNDNUtils.createName(userID, sensorID, timeString,
                        processID);
                Data dataPacket = JNDNUtils.createDataPacket(dataPayload, packetName);

                new UDPSocket(ConstVar.PHINET_PORT, allValidPITEntries.get(i).getIpAddr(), ConstVar.DATA_TYPE)
                        .execute(dataPacket.wireEncode().getImmutableArray()); // reply to interest with DATA from cache

                // store sent packet in database for further review
                Utils.storeDataPacket(context, dataPacket);
            }
        }
    }
}