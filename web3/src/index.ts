import express, { Request, Response, NextFunction } from 'express';
require('dotenv').config();
import bodyParser from 'body-parser';

import { Setup } from '@bsv/wallet-toolbox';
import { createPaymentMiddleware } from '@bsv/payment-express-middleware';
import { AuthRequest, createAuthMiddleware } from '@bsv/auth-express-middleware';

import { Chain } from '@bsv/wallet-toolbox/out/src/sdk/types.js';
import { OpReturn } from '@bsv/templates';

// Global crypto polyfill
(global.self as any) = { crypto };

// ---------------- ENV ----------------
const {
  SERVER_PRIVATE_KEY,
  HTTP_PORT,
  BSV_NETWORK
} = process.env;

// ---------------- VALIDATION ----------------
function validateEnvironment() {
  const missing = [];

  if (!SERVER_PRIVATE_KEY) missing.push('SERVER_PRIVATE_KEY');
  if (!HTTP_PORT) missing.push('HTTP_PORT');
  if (!BSV_NETWORK) missing.push('BSV_NETWORK');

  if (missing.length > 0) {
    throw new Error(`Missing env vars: ${missing.join(', ')}`);
  }
}

// ---------------- APP ----------------
const app = express();

app.use(bodyParser.json({ limit: '64mb' }));

// CORS middleware
app.use((req: Request, res: Response, next: NextFunction) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', '*');
  res.header('Access-Control-Allow-Methods', '*');

  if (req.method === 'OPTIONS') {
    return res.sendStatus(200);
  }

  next();
});

// ---------------- INIT ----------------
async function init() {
  try {
    validateEnvironment();
  } catch (err) {
    console.error(err);
    process.exit(1);
  }

  console.log('.env loaded');

  // ✅ LOCAL MODE WALLET (NO STORAGE SERVER REQUIRED)
  const wallet = await Setup.createWalletClientNoEnv({
    chain: BSV_NETWORK as Chain,
    rootKeyHex: SERVER_PRIVATE_KEY as string
  });

  console.log('wallet initialized (local mode)');

  // AUTH middleware
 /* app.use(createAuthMiddleware({
    wallet,
    allowUnauthenticated: true,
    logger: console,
    logLevel: 'debug'
  }));*/

  console.log('auth middleware ready');

  // PAYMENT middleware
  //app.use(createPaymentMiddleware({
   // wallet,
   // calculateRequestPrice: async () => 260
 // }));

  console.log('payment middleware ready');

  // ---------------- ROUTE ----------------
 app.post('/createHmac', async (req: Request, res: Response) => {
  try {
    console.log('createHmac called');

    const result = await wallet.createHmac(req.body);

    return res.status(200).json(result);
  } catch (error) {
    console.error('createHmac error:', error);
    return res.status(500).json({
      status: 'error',
      message: (error as Error).message
    });
  }
});

 app.get('/balance', async (req: Request, res: Response) => {
  try {
    const balance = await wallet.listOutputs({
      basket: 'default'
    });

    return res.json(balance);
  } catch (err) {
    console.error(err);
    return res.status(500).json(err);
  }
});
 
  app.get('/address', async (req: Request, res: Response) => {
  try {
    const pub = await wallet.getPublicKey({});

    return res.json(pub);
  } catch (err) {
    console.error(err);
    return res.status(500).json(err);
  }
});
    
   app.post('/createSignature', async (req: Request, res: Response) => {
  try {
    const result = await wallet.createSignature(req.body);
    return res.status(200).json(result);
  } catch (error) {
    return res.status(500).json({ error: (error as Error).message });
  }
});

app.post('/verifySignature', async (req: Request, res: Response) => {
  try {
    const result = await wallet.verifySignature(req.body);
    return res.status(200).json(result);
  } catch (error) {
    return res.status(500).json({ error: (error as Error).message });
  }
});

 app.post('/getPublicKey', async (req: Request, res: Response) => {
  try {
    console.log('getPublicKey called');

    const result = await wallet.getPublicKey(req.body);

    return res.status(200).json(result);
  } catch (error) {
    console.error('getPublicKey error:', error);
    return res.status(500).json({
      status: 'error',
      message: (error as Error).message
    });
  }
});

   app.post('/verifyHmac', async (req: Request, res: Response) => {
  try {
    console.log('verifyHmac called');

    const result = await wallet.verifyHmac(req.body);

    return res.status(200).json(result);
  } catch (error) {
    console.error('verifyHmac error:', error);
    return res.status(500).json({
      status: 'error',
      message: (error as Error).message
    });
  }
});

 app.post('/encrypt', async (req: Request, res: Response) => {
  try {
    const result = await wallet.encrypt(req.body);
    return res.status(200).json(result);
  } catch (error) {
    return res.status(500).json({ error: (error as Error).message });
  }
});

app.post('/decrypt', async (req: Request, res: Response) => {
  try {
    const result = await wallet.decrypt(req.body);
    return res.status(200).json(result);
  } catch (error) {
    return res.status(500).json({ error: (error as Error).message });
  }
});

  app.post('/anchor-tsi-rating', async (req: Request, res: Response) => {
    try {
      console.log('request received');

      const tsiData = req.body.tsiData;

      if (!tsiData?.tsiHash) {
        return res.status(400).json({
          status: 'error',
          message: 'tsiHash missing'
        });
      }

      const tsiHash = tsiData.tsiHash;
      console.log('Hash:', tsiHash);

      const instance = new OpReturn();

      const fakeTxid = "LOCAL_TEST_ANCHOR_" + Date.now();

return res.status(200).json({
  status: "OK",
  txid: fakeTxid,
  message: "DMA form published locally. Blockchain anchoring skipped for local testing."
});

      /*const response = await wallet.createAction({
        description: 'TSI Rating Anchor',
        outputs: [{
          satoshis: 0,
          lockingScript: instance.lock(tsiHash).toHex(),
          basket: 'TSI_RATING_DMA',
          outputDescription: 'TSI Rating'
        }]
      });

      console.log('txid:', response.txid);

      return res.status(200).json({
        status: 'OK',
        txid: response.txid
      });*/

    } catch (error) {
      console.error(error);

      return res.status(500).json({
        status: 'error',
        message: (error as Error).message
      });
    }
  });

  // ---------------- START SERVER ----------------
  app.listen(Number(HTTP_PORT), () => {
    console.log(`Server running on port ${HTTP_PORT}`);
  });
}

init().catch(err => {
  console.error('Failed to start server:', err);
});